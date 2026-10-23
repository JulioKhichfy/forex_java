import { Component, OnDestroy, computed, effect, inject, signal } from '@angular/core';
import { DatePipe, DecimalPipe } from '@angular/common';
import { HttpClient } from '@angular/common/http';
import { RouterLink } from '@angular/router';
import { Api, JobStatus, MODEL_NAMES } from '../api';
import { MARKETS, MarketState } from '../market';
import { Tip } from './tip';
import { Brt } from '../brt';

/** Laboratório de configuração (GET /api/ops/lab): o JSON que o comando lab grava. */
interface LabConfig { model: string; horizon: number; kind: string; pMin: number; mMin: number; skipFlat: boolean; stopAtr: number; targetR: number }
interface LabStats { n: number; er: number; sumR: number; hit: number; pf: number | null; maxDd: number; perMonth: number }
interface LabRow { config: LabConfig; choose: LabStats | null; confirm: LabStats | null; live: LabStats | null }
interface LabReport {
  market: string; builtAt: string; wfRun: string; lockboxRun: string | null; chooseFrom: string; chooseTo: string;
  confirmFrom: string; confirmTo: string; liveFrom: string | null; liveTo: string | null; configs: number; minTrades: number;
  top: LabRow[]; survivors: number; survivalOf: number; baseSurvivors: number; baseOf: number;
  recommended: LabRow | null; current: LabRow[]; seconds: number; frequent?: LabRow[];
}
interface LabFile { report: LabReport; usd_per_atr_min_lot: Record<string, number>; current_exits: { stopAtr: number; targetR: number } }

const KIND: Record<string, string> = { ALL: 'todos os momentos', EVENT: 'só depois de evento', CONTROL: 'só na hora cheia', QUARTER: 'só nos quartos de hora (:15, :30, :45)' };

@Component({
  selector: 'page-laboratorio',
  imports: [DatePipe, DecimalPipe, RouterLink, Tip, Brt],
  template: `
    <h1>Laboratório · {{ marketLabel() }}</h1>
    <div class="notice">
      <b>Que regra de entrada e de saída teria dado mais resultado neste mercado — sem escolher sorte.</b>
      Testo {{ file()?.report?.configs ?? '5.000+' }} combinações (modelo, 15/60 min, momento, probabilidade e margem mínimas, pular LATERAL,
      stop e alvo) nas previsões <b>fora da amostra</b>, simulando cada saída no M1 com spread. A combinação é
      <b>escolhida</b> num período e só é recomendada se também for positiva no período de <b>confirmação</b>, que não
      entrou na escolha; o <b>ao vivo</b> é a terceira conferência. Mesmo assim é o passado: não é garantia.
    </div>

    <div class="bar">
      <button class="primary" (click)="run()" [disabled]="job()?.running">
        {{ job()?.running ? 'Rodando…' : 'Rodar o laboratório deste mercado' }}</button>
      @if (job(); as j) {
        <span class="small" [class.muted]="!j.running">
          @if (j.running) { {{ j.label }} — {{ j.step ?? 'começando' }} (índices e ações levam ~7 min) }
          @else if (j.name === 'laboratorio') { última execução: {{ j.exitCode === 0 ? 'ok' : 'falhou (código ' + j.exitCode + ')' }} }
        </span>
      }
      @if (file(); as f) {
        <span class="small muted">· análise de <span [brt]="f.report.builtAt">{{ f.report.builtAt | date: 'dd/MM HH:mm' : 'UTC' }} UTC</span>
          ({{ f.report.seconds | number: '1.0-0' }} s)</span>
      }
    </div>

    @if (missing()) {
      <p class="muted">Ainda não há laboratório deste mercado. Clique em <b>Rodar o laboratório deste mercado</b>.</p>
    }

    @if (file(); as f) {
      @let r = f.report;
      <p class="small muted">Escolha: {{ r.chooseFrom }} a {{ r.chooseTo }} · confirmação: {{ r.confirmFrom }} a {{ r.confirmTo }}
        (inclui o cofre) · ao vivo: @if (r.liveFrom) { {{ r.liveFrom | date: 'dd/MM' : 'UTC' }} a {{ r.liveTo | date: 'dd/MM' : 'UTC' }} } @else { — }
        · mínimo de {{ r.minTrades }} operações na escolha e 20 na confirmação.</p>

      <section class="panel verdict" [class.good]="r.recommended" [class.bad]="!r.recommended">
        @if (r.recommended; as rec) {
          <h3>Configuração recomendada <tip text="A melhor na escolha que também foi positiva na confirmação. Os números de cada período estão abaixo; amostra pequena é aviso de cautela." /></h3>
          <p class="rule">{{ ruleText(rec.config) }}</p>
          @let ev = evidence(r, rec);
          <p><span class="badge" [class.ok]="ev.level === 'forte'" [class.warn]="ev.level === 'moderada'" [class.high]="ev.level === 'fraca'">evidência {{ ev.level }}</span>
            <span class="small"> {{ ev.text }}</span></p>
          @if (warnings(rec).length) {
            <ul class="warn small">@for (w of warnings(rec); track w) { <li>{{ w }}</li> }</ul>
          }
          <div class="table-wrap"><table>
            <tr><th>Período</th><th class="right">Operações</th><th class="right">por mês</th><th class="right">E[R] <tip key="R" /></th>
              <th class="right">Acerto</th><th class="right">Profit factor</th><th class="right">Pior queda</th><th class="right">Em US$ (lote mínimo) <tip text="1 R = a perda no stop com o lote mínimo, pelo ATR mediano de cada símbolo (média dos símbolos). Resultado por operação e pior queda." /></th></tr>
            @for (p of periods(rec); track p.label) {
              <tr [class.hit]="p.s && p.s.er > 0" [class.miss]="p.s && p.s.er <= 0">
                <td>{{ p.label }}</td>
                @if (p.s; as s) {
                  <td class="right mono">{{ s.n }}</td><td class="right mono">{{ s.perMonth | number: '1.1-1' }}</td>
                  <td class="right mono">{{ s.er | number: '1.3-3' }}</td><td class="right mono">{{ s.hit * 100 | number: '1.0-0' }}%</td>
                  <td class="right mono">{{ s.pf === null ? '—' : (s.pf | number: '1.2-2') }}</td>
                  <td class="right mono">{{ s.maxDd | number: '1.1-1' }} R</td>
                  <td class="right mono small">{{ usdText(rec.config, s) }}</td>
                } @else { <td colspan="7" class="muted">sem operações</td> }
              </tr>
            }
          </table></div>
          <h4>Como seguir</h4>
          <ul class="small">
            <li>Saídas na página <a routerLink="/risco">Risco</a>, seção 3.4, mercado {{ marketLabel() }}: stop <b>{{ rec.config.stopAtr }} ATR</b>,
              alvo <b>{{ rec.config.targetR }} × o stop</b>, saída por tempo <b>{{ rec.config.horizon }} min</b>.
              @if (f.current_exits.stopAtr !== rec.config.stopAtr || f.current_exits.targetR !== rec.config.targetR) {
                <span class="down">Hoje: stop {{ f.current_exits.stopAtr }} ATR e alvo {{ f.current_exits.targetR }}.</span> } @else { <span class="up">Já são as de hoje.</span> }</li>
            <li>Entrada: só operar quando a previsão do modelo <b>{{ names[rec.config.model] }}</b> a {{ rec.config.horizon }} min,
              {{ kindText(rec.config.kind) }}, tiver P(lado) ≥ {{ rec.config.pMin * 100 | number: '1.0-0' }}%
              e margem ≥ {{ rec.config.mMin * 100 | number: '1.0-0' }} pontos. A roleta não tem esses filtros (entra em toda previsão):
              compare a linha "a roleta de hoje" abaixo.</li>
            <li>Com a banca de hoje, confira na página Risco se 1 R com o lote mínimo cabe no seu teto por operação.</li>
          </ul>
          <div class="startbox">
            <label class="small">Lote <input type="number" min="0.01" step="0.01" [value]="rlLot()" (input)="rlLot.set(+$any($event.target).value)" /></label>
            <button class="primary" (click)="startRoulette(rec, r)" [disabled]="rlBusy()">{{ rlBusy() ? 'Ligando…' : 'Ligar a roleta com esta configuração' }}</button>
            <tip text="Liga a roleta deste mercado com esta regra: modelo e horizonte, só os momentos do filtro, P e margem mínimas, pular LATERAL, e stop e alvo próprios (saída por tempo no horizonte). Substitui a roleta que estiver ligada neste mercado. A roleta ignora parte das travas de risco (teto e alvo de risco, exposição ao dólar, margem, spread, blackout); se quiser todas as travas, use o sinal automático. Para parar: página Roleta." />
            @if (rlMsg(); as m) { <span class="small" [class.down]="m.error" [class.up]="!m.error">{{ m.text }}</span> }
          </div>
        } @else {
          <h3>Nenhuma configuração passou: não operar este mercado</h3>
          <p class="small">Nenhuma das combinações positivas na escolha (com operações bastantes) se manteve positiva na confirmação.
            @if (r.top.length) { A melhor na escolha foi <b>{{ ruleText(r.top[0].config) }}</b>: {{ er(r.top[0].choose) }} na escolha e
              {{ er(r.top[0].confirm) }} na confirmação. }</p>
        }
        <p class="small muted">Sobrevivência: {{ r.survivors }} de {{ r.survivalOf }} das 20 melhores na escolha seguiram positivas
          na confirmação; entre todas as positivas na escolha, {{ r.baseSurvivors }} de {{ r.baseOf }}
          <tip text="As melhores costumam ser variações da mesma regra (limiares vizinhos), então a primeira taxa engana para cima. A segunda é a taxa de base: com poucas combinações positivas e quase todas da mesma família, o resultado depende de UMA regra e de poucas operações." />.</p>
      </section>

      @if (r.current.length) {
        <section class="panel" style="margin-top:16px">
          <h3>A roleta de hoje neste mercado <tip text="Modelo B, lado mais provável em toda previsão, pulando LATERAL, com as saídas de hoje do mercado." /></h3>
          <div class="table-wrap"><table>
            <tr><th>Horizonte</th><th class="right">Escolha</th><th class="right">Confirmação</th><th class="right">Ao vivo</th><th class="right">Pior queda (confirmação)</th></tr>
            @for (c of r.current; track c.config.horizon) {
              <tr [class.miss]="(c.confirm?.er ?? 0) <= 0" [class.hit]="(c.confirm?.er ?? 0) > 0">
                <td>{{ c.config.horizon }} min</td><td class="right mono">{{ er(c.choose) }}</td><td class="right mono">{{ er(c.confirm) }}</td>
                <td class="right mono">{{ er(c.live) }}</td><td class="right mono">{{ c.confirm ? (c.confirm.maxDd | number: '1.0-0') + ' R' : '—' }}</td>
              </tr>
            }
          </table></div>
        </section>
      }

      @if (r.frequent?.length) {
        <section class="panel" style="margin-top:16px">
          <h3>Mais operações com vantagem <tip text="As que mais operam (por mês, na confirmação) entre as com E[R] de +0,10 R ou mais nos DOIS períodos. Operar mais rende mais no mês só se cada operação ainda tiver vantagem; abaixo disso é pagar spread e correr risco." /></h3>
          <div class="table-wrap"><table>
            <tr><th>Regra</th><th class="right">Por mês</th><th class="right">Escolha</th><th class="right">Confirmação</th><th class="right">Ao vivo</th><th class="right">Pior queda</th><th class="right">Esperado/mês</th></tr>
            @for (row of r.frequent!; track $index) {
              <tr>
                <td class="small">{{ ruleText(row.config) }}</td>
                <td class="right mono">{{ row.confirm?.perMonth | number: '1.1-1' }}</td>
                <td class="right mono">{{ er(row.choose) }}</td><td class="right mono">{{ er(row.confirm) }}</td>
                <td class="right mono">{{ er(row.live) }}</td>
                <td class="right mono">{{ row.confirm ? (row.confirm.maxDd | number: '1.1-1') + ' R' : '—' }}</td>
                <td class="right mono">{{ row.confirm ? '+' + (row.confirm.er * row.confirm.perMonth | number: '1.1-1') + ' R' : '—' }}</td>
              </tr>
            }
          </table></div>
        </section>
      }

      <section class="panel" style="margin-top:16px">
        <h3>As melhores na escolha (e o que aconteceu depois) <tip text="Ordenadas pelo E[R] no período de escolha. Verde = seguiu positiva na confirmação; vermelho = não seguiu. É aqui que se vê se o ranking significa algo." /></h3>
        <div class="table-wrap"><table>
          <tr><th>Regra</th><th class="right">Escolha</th><th class="right">Confirmação</th><th class="right">Ao vivo</th><th class="right">Pior queda</th></tr>
          @for (row of r.top; track $index) {
            <tr [class.hit]="ok(row)" [class.miss]="!ok(row)">
              <td class="small">{{ ruleText(row.config) }}</td>
              <td class="right mono">{{ er(row.choose) }}</td><td class="right mono">{{ er(row.confirm) }}</td>
              <td class="right mono">{{ er(row.live) }}</td><td class="right mono">{{ row.confirm ? (row.confirm.maxDd | number: '1.1-1') + ' R' : '—' }}</td>
            </tr>
          } @empty { <tr><td colspan="5" class="muted">Nenhuma combinação com operações bastantes na escolha.</td></tr> }
        </table></div>
      </section>
    }
  `,
  styles: `
    .bar { display: flex; gap: 10px; align-items: center; flex-wrap: wrap; margin: 12px 0; }
    .verdict.good { border-color: var(--up); }
    .verdict.bad { border-color: var(--down); }
    .rule { font-size: 16px; font-weight: 600; }
    ul.warn { color: var(--warn, #d97706); margin: 4px 0 8px; }
    tr.hit td { background: var(--up-bg); }
    tr.miss td { background: var(--down-bg); }
    h4 { margin: 14px 0 4px; }
    .startbox { display: flex; gap: 10px; align-items: center; flex-wrap: wrap; margin-top: 10px; }
    .startbox input { width: 70px; }
  `,
})
export class Laboratorio implements OnDestroy {
  private api = inject(Api);
  private http = inject(HttpClient);
  private marketState = inject(MarketState);
  protected names = MODEL_NAMES;
  protected file = signal<LabFile | null>(null);
  protected missing = signal(false);
  protected job = signal<JobStatus | null>(null);
  protected rlLot = signal(0.01);
  protected rlBusy = signal(false);
  protected rlMsg = signal<{ text: string; error: boolean } | null>(null);
  private timer?: ReturnType<typeof setInterval>;
  private wasRunning = false;

  protected marketLabel = computed(() => MARKETS.find((m) => m.code === this.marketState.market())?.label ?? this.marketState.market());

  /** US$ de 1 R com o lote mínimo (média dos símbolos do mercado). */
  private usdPerR = (c: LabConfig) => {
    const v = Object.values(this.file()?.usd_per_atr_min_lot ?? {});
    return v.length ? c.stopAtr * v.reduce((a, b) => a + b, 0) / v.length : null;
  };

  constructor() {
    effect(() => { this.marketState.market(); this.load(); });
    this.pollJob();
    this.timer = setInterval(() => this.pollJob(), 5_000);
  }

  ngOnDestroy(): void {
    clearInterval(this.timer);
  }

  private load(): void {
    this.http.get<LabFile>(`/api/ops/lab?market=${this.marketState.market()}`).subscribe({
      next: (f) => { this.file.set(f); this.missing.set(false); },
      error: () => { this.file.set(null); this.missing.set(true); },
    });
  }

  private pollJob(): void {
    this.api.jobs().subscribe({
      next: (j) => {
        this.job.set(j.current);
        const running = !!j.current?.running;
        if (this.wasRunning && !running) this.load();   // terminou: recarrega o relatório
        this.wasRunning = running;
      },
      error: () => {},
    });
  }

  protected run(): void {
    if (!confirm(`Rodar o laboratório de ${this.marketLabel()}? Leva de 1 a 7 minutos e usa CPU; evite as horas cheias (previsões).`)) return;
    this.http.post<JobStatus>(`/api/ops/jobs/laboratorio?market=${this.marketState.market()}`, {}).subscribe({
      next: (j) => { this.job.set(j); this.wasRunning = true; },
      error: (e) => alert(e?.error?.message ?? 'não consegui iniciar'),
    });
  }

  /** Liga a roleta do mercado com a configuração recomendada (filtros e saídas próprias). */
  protected startRoulette(rec: LabRow, r: LabReport): void {
    const c = rec.config;
    const market = this.marketState.market();
    const ev = this.evidence(r, rec);
    this.http.get<any>('/api/roulette?days=1').subscribe({
      next: (st) => {
        const real = st?.mode === 'LIVE';
        const text = `Ligar a roleta de ${this.marketLabel()} com:\n${this.ruleText(c)}\nlote ${this.rlLot()}, saída por tempo em ${c.horizon} min.\n\n`
          + `Evidência ${ev.level}: ${ev.text}\n`
          + 'A roleta ignora teto e alvo de risco, exposição ao dólar, margem, spread e blackout (valem posições, um por símbolo, '
          + 'mercado ligado, risco aberto, sessão e perda do dia/semana). Se o stop em US$ estiver ligado (página Risco), ele prevalece '
          + 'sobre o stop e o alvo desta configuração. Substitui a roleta ligada neste mercado.';
        if (real) {
          if (prompt(`${text}\n\nCONTA REAL: digite LIVE para confirmar.`) !== 'LIVE') return;
        } else if (!confirm(text)) return;
        const q = new URLSearchParams({
          market, horizon: String(c.horizon), model: c.model, lot: String(this.rlLot()), strategy: 'trend',
          kind: c.kind, pMin: String(c.pMin), mMin: String(c.mMin), skipFlat: String(c.skipFlat),
          stopAtr: String(c.stopAtr), targetR: String(c.targetR),
          source: `Laboratório ${new Date(r.builtAt).toLocaleDateString('pt-BR')}`,
        });
        this.rlBusy.set(true);
        this.http.post<any>(`/api/roulette/start?${q}`, {}).subscribe({
          next: () => { this.rlBusy.set(false); this.rlMsg.set({ text: 'Roleta ligada com esta configuração. Acompanhe na página Roleta.', error: false }); },
          error: (e) => { this.rlBusy.set(false); this.rlMsg.set({ text: e?.error?.message ?? 'não consegui ligar (reinicie o servidor?)', error: true }); },
        });
      },
      error: () => this.rlMsg.set({ text: 'roleta indisponível', error: true }),
    });
  }

  protected kindText(k: string): string {
    return KIND[k] ?? k;
  }

  protected ruleText(c: LabConfig): string {
    const entry = c.pMin > 0 || c.mMin > 0
      ? `P ≥ ${(c.pMin * 100).toFixed(0)}%, margem ≥ ${(c.mMin * 100).toFixed(0)}`
      : 'toda previsão';
    return `${this.names[c.model] ?? c.model} · ${c.horizon} min · ${this.kindText(c.kind)} · ${entry}`
      + `${c.skipFlat ? ' · pula LATERAL' : ''} · stop ${c.stopAtr} ATR · alvo ${c.targetR}×`;
  }

  protected er(s: LabStats | null): string {
    return s ? `${s.er >= 0 ? '+' : ''}${s.er.toFixed(3)} R (${s.n})` : '—';
  }

  protected ok(r: LabRow): boolean {
    return !!r.confirm && r.confirm.n >= 20 && r.confirm.er > 0;
  }

  protected periods(r: LabRow): { label: string; s: LabStats | null }[] {
    return [{ label: 'Escolha', s: r.choose }, { label: 'Confirmação (+ cofre)', s: r.confirm }, { label: 'Ao vivo', s: r.live }];
  }

  protected usdText(c: LabConfig, s: LabStats): string {
    const r = this.usdPerR(c);
    if (r === null) return '—';
    return `1 R ≈ US$ ${r.toFixed(2)} · ${s.er >= 0 ? '+' : '−'}US$ ${Math.abs(s.er * r).toFixed(2)}/op · queda US$ ${Math.abs(s.maxDd * r).toFixed(0)}`;
  }

  /**
   * Força da evidência: taxa de sobrevivência entre TODAS as positivas na escolha (as vizinhas inflam a das 20 melhores)
   * e o tamanho da amostra. Abaixo de 30%: o ranking é quase ruído e a recomendada provavelmente é sorte.
   */
  protected evidence(r: LabReport, rec: LabRow): { level: 'forte' | 'moderada' | 'fraca'; text: string } {
    const base = r.baseOf ? r.baseSurvivors / r.baseOf : 0;
    const n = Math.min(rec.choose?.n ?? 0, rec.confirm?.n ?? 0);
    const pct = `${(base * 100).toFixed(0)}% das combinações positivas na escolha seguiram positivas`;
    if (base < 0.3) return { level: 'fraca', text: `Só ${pct}: o ranking é quase ruído; esta regra provavelmente é sorte.` };
    if (base >= 0.5 && n >= 50) return { level: 'forte', text: `${pct}, com ${n}+ operações em cada período.` };
    return { level: 'moderada', text: `${pct}, mas com só ${n} operações num período: uma família de regra, pouca amostra.` };
  }

  protected warnings(r: LabRow): string[] {
    const w: string[] = [];
    if ((r.choose?.n ?? 0) < 50 || (r.confirm?.n ?? 0) < 50) w.push('Amostra pequena (menos de 50 operações num período): o resultado pode ser sorte.');
    if ((r.live?.n ?? 0) < 10) w.push('Ao vivo ainda quase sem operações desta regra: a conferência ao vivo não diz nada ainda.');
    else if ((r.live?.er ?? 0) <= 0) w.push('Ao vivo está negativo até agora (poucas operações: acompanhe).');
    if ((r.confirm?.perMonth ?? 0) < 2) w.push('Menos de 2 operações por mês: a regra quase não opera.');
    return w;
  }
}
