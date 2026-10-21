import { Component, OnDestroy, OnInit, computed, effect, inject, signal } from '@angular/core';
import { DatePipe, DecimalPipe } from '@angular/common';
import { RouterLink } from '@angular/router';
import { HttpClient } from '@angular/common/http';
import { Api, CalendarItem, LatestPredictions, MODEL_NAMES, NewsItem, OrderPreview, Prediction } from '../api';
import { catchError, concatMap, forkJoin, from, map, of } from 'rxjs';
import { ProbBar } from './prob-bar';
import { Tip } from './tip';
import { Brt } from '../brt';
import { MarketState } from '../market';
import { JobsPanel } from './jobs-panel';

@Component({
  selector: 'page-hoje',
  imports: [DatePipe, DecimalPipe, RouterLink, ProbBar, Tip, JobsPanel, Brt],
  template: `
    <div class="notice">
      <b>Apoio à decisão, não sinal garantido.</b> No teste fora da amostra nenhum modelo superou o custo de forma
      consistente. Confira o <a routerLink="/previsoes">placar ao vivo</a> e o resultado do cofre em
      <a routerLink="/modelos">Modelos</a> antes de confiar numa previsão.
    </div>

    @if (fresh(); as f) {
      <div class="alert" [class.buy]="f.signal === 'BUY'" [class.sell]="f.signal === 'SELL'">
        <div>
          <b>SINAL {{ f.signal === 'BUY' ? 'DE COMPRA' : 'DE VENDA' }} · {{ f.symbol }}</b>
          <span class="small"> {{ names[f.model] ?? f.model }}, {{ f.horizonMin }} min · momento <span [brt]="f.momentUtc">{{ f.momentUtc | date: 'HH:mm' : 'UTC' }} UTC</span>
            ({{ ageMin(f) }} min atrás) · P(alta) {{ f.pUp * 100 | number: '1.0-0' }}% · P(queda) {{ f.pDown * 100 | number: '1.0-0' }}%</span>
          <div class="small">Vale enquanto for recente: no histórico, entrar 5 min depois já levava o resultado para perto de zero.
            A ordem sai sozinha em {{ f.horizonMin }} min se o stop ou o alvo não vierem antes.</div>
        </div>
        <button class="primary" (click)="previewOne(f)" [disabled]="busy()">Operar agora</button>
      </div>
    }

    @if (auto(); as a) {
      @if (a.markets[marketState.market()]; as am) {
        <section class="panel auto" [class.on]="am.config.on">
          <h3>Sinal automático <tip text="Quando a regra deste mercado dá sinal, a ordem é enviada sozinha, no lado do sinal, com o lote escolhido. TODAS as travas de risco valem: o que for recusado fica registrado com o motivo. Sinal mais velho que o limite não vira ordem (entrar atrasado leva a vantagem embora)." /></h3>
          <div class="autobar">
            @if (am.config.on) {
              <span class="badge ok">LIGADO</span>
              <span class="small">lote {{ am.config.lot ?? 'mínimo' }} · desde <span [brt]="am.config.startedAt">{{ am.config.startedAt | date: 'dd/MM HH:mm' : 'UTC' }} UTC</span>
                · sinal vale até {{ a.max_age_seconds }} s · modo {{ a.mode }}</span>
              <button class="danger" (click)="stopAuto()">Desligar</button>
            } @else {
              <span class="badge">desligado</span>
              <label class="small">Lote <input type="number" min="0.01" step="0.01" [value]="autoLot()" (input)="autoLot.set(+$any($event.target).value)" /></label>
              <button class="primary" (click)="startAuto()">Ligar sinal automático</button>
              @if (am.config.stopReason) { <span class="small muted">({{ am.config.stopReason }})</span> }
            }
          </div>
          @if (autoEntries().length) {
            <div class="table-wrap"><table class="small">
              <tr><th>Quando (UTC)</th><th>Símbolo</th><th>Lado</th><th>Status</th><th>Detalhe</th><th class="right">Resultado</th></tr>
              @for (e of autoEntries(); track e.id) {
                <tr>
                  <td class="mono" [brt]="e.created_at">{{ e.created_at | date: 'dd/MM HH:mm:ss' : 'UTC' }}</td>
                  <td class="mono">{{ e.symbol }}</td>
                  <td>@if (e.side) { <span class="badge" [class.buy]="e.side === 'BUY'" [class.sell]="e.side === 'SELL'">{{ e.side === 'BUY' ? 'COMPRA' : 'VENDA' }}</span> }</td>
                  <td><span class="badge" [class.ok]="e.status === 'SENT'" [class.high]="e.status === 'REFUSED' || e.status === 'LATE'">{{ autoStatus(e.status) }}</span></td>
                  <td class="muted">{{ e.message }}</td>
                  <td class="right mono" [class.up]="(e.result ?? 0) > 0" [class.down]="(e.result ?? 0) < 0">{{ e.result === null || e.result === undefined ? '' : 'US$ ' + (e.result | number: '1.2-2') }}</td>
                </tr>
              }
            </table></div>
          } @else { <p class="small muted">Nenhum sinal visto ainda (são poucos por mês, só depois de divulgações).</p> }
        </section>
      }
    }

    <div class="grid two">
      <section class="panel">
        <h3>Sinais agora ({{ ruleText() ? 'regra do mercado' : 'gate 4' }}) <tip [key]="ruleText() ? 'regraOuro' : 'gate4'" /></h3>
        @if (latest(); as l) {
          <p class="muted small">
            Versão {{ l.model_version ?? '—' }} ·
            @if (l.age_minutes !== null) { previsão de {{ l.age_minutes }} min atrás } @else { nenhuma previsão ainda }
          </p>
          @if (ruleText(); as rt) {
            <p class="small rule">{{ rt }}
              @if (l.rule?.note) { <tip [text]="l.rule!.note!" /> }</p>
          }
          @if (signals().length === 0) {
            <p class="muted">{{ ruleText() ? 'Nenhuma previsão passou na regra no último momento de decisão.' : 'Nenhum modelo com P ≥ 60% e margem ≥ 35% no último momento de decisão.' }}</p>
          } @else {
            <div class="table-wrap"><table>
              <tr><th>Par</th><th>Modelo</th><th>Horizonte <tip key="horizonte" /></th><th>Sinal</th><th>Probabilidades <tip key="probabilidades" /></th></tr>
              @for (p of signals(); track p.id) {
                <tr>
                  <td class="mono">{{ p.symbol }}</td>
                  <td>{{ names[p.model] ?? p.model }}</td>
                  <td>{{ p.horizonMin }} min</td>
                  <td><span class="badge" [class.buy]="p.signal === 'BUY'" [class.sell]="p.signal === 'SELL'">
                    {{ p.signal === 'BUY' ? 'COMPRA' : 'VENDA' }}</span></td>
                  <td><prob-bar [down]="p.pDown" [flat]="p.pFlat" [up]="p.pUp" /></td>
                </tr>
              }
            </table></div>
          }
          <h3 style="margin-top:16px">Mais provável por símbolo ({{ names[mainSpec().model] ?? mainSpec().model }}, {{ mainSpec().horizon }} min) <tip key="modeloB" /></h3>
          <div class="trade-bar">
            <label class="small">Lote por ordem <tip key="lote" />
              <input type="number" min="0.01" step="0.01" [value]="lot()" (input)="setLot($event)" />
            </label>
            <button class="primary" (click)="previewAll()" [disabled]="busy() || mainModel().length === 0">
              {{ busy() ? 'Calculando…' : 'Operar todos (' + mainModel().length + ')' }}</button>
            <span class="muted small">Direção pela previsão: COMPRA se P(alta) &gt; P(queda), senão VENDA. Toda ordem passa
              pelas travas da página Risco <tip text="Limite de posições simultâneas, uma posição por símbolo, risco aberto, perdas do dia/semana, spread, blackout e as demais. O que não passar é recusado com o motivo." />.</span>
          </div>
          <div class="table-wrap"><table>
            <tr><th>Símbolo</th><th>Momento <tip key="evento" /></th><th>Probabilidades (queda · lateral · alta) <tip key="probabilidades" /></th>
              <th>Direção <tip text="O lado mais provável entre alta e queda. A força é a diferença entre as duas probabilidades; o gate 4 pede ≥ 35 pontos e P ≥ 60%." /></th><th></th></tr>
            @for (p of mainModel(); track p.id) {
              <tr>
                <td class="mono">{{ p.symbol }}</td>
                <td class="small muted nowrap"><span [brt]="p.momentUtc">{{ p.momentUtc | date: 'dd/MM HH:mm' : 'UTC' }}</span> {{ p.kind === 'EVENT' ? '· evento' : '' }}</td>
                <td><prob-bar [down]="p.pDown" [flat]="p.pFlat" [up]="p.pUp" /></td>
                <td class="nowrap">
                  <span class="badge" [class.buy]="side(p) === 'BUY'" [class.sell]="side(p) === 'SELL'">{{ side(p) === 'BUY' ? 'COMPRA' : 'VENDA' }}</span>
                  <span class="small muted"> força {{ strength(p) | number: '1.0-0' }}</span>
                  @if (weak(p); as w) { <span class="badge warn" [title]="w">fraca</span> }
                </td>
                <td class="nowrap">
                  <button (click)="previewOne(p)" [disabled]="busy()">Operar</button>
                  @if (results()[p.symbol]; as r) { <span class="small" [class.up]="r.ok" [class.down]="!r.ok"> {{ r.text }}</span> }
                </td>
              </tr>
            } @empty {
              <tr><td colspan="5" class="muted">Sem previsões. Mercado fechado ou modelo ainda não treinado.</td></tr>
            }
          </table></div>

          @if (plan().length) {
            <div class="plan">
              <h3>Confirmar {{ plan().length === 1 ? 'a ordem' : 'as ordens' }} · {{ lot() }} lote cada</h3>
              <table>
                <tr><th>Símbolo</th><th>Lado</th><th class="right">Perda máx. <tip key="R" /></th><th class="right">Ganho no alvo</th><th>Travas</th></tr>
                @for (x of plan(); track x.symbol) {
                  <tr [class.blocked]="!x.preview.ok">
                    <td class="mono">{{ x.symbol }}</td>
                    <td><span class="badge" [class.buy]="x.side === 'BUY'" [class.sell]="x.side === 'SELL'">{{ x.side === 'BUY' ? 'COMPRA' : 'VENDA' }}</span></td>
                    <td class="right mono down">{{ x.preview.riskUsd !== null ? 'US$ ' + (x.preview.riskUsd | number: '1.2-2') : '—' }}</td>
                    <td class="right mono up">{{ x.preview.rewardUsd !== null ? 'US$ ' + (x.preview.rewardUsd | number: '1.2-2') : '—' }}</td>
                    <td class="small">{{ x.preview.ok ? 'todas ok' : 'bloqueada: ' + failed(x.preview) }}</td>
                  </tr>
                }
              </table>
              <p class="small">
                Serão enviadas <b>{{ okCount() }}</b> ordem(ns) · perda máxima somada <b class="down">US$ {{ okRisk() | number: '1.2-2' }}</b>
                @if (okCount() > 1) { <span class="muted">(as travas são conferidas de novo a cada envio: depois da 1ª, o limite de posições pode recusar as seguintes)</span> }
              </p>
              <div class="trade-bar">
                <button class="primary" (click)="sendPlan()" [disabled]="busy() || okCount() === 0">Enviar {{ okCount() }}</button>
                <button (click)="plan.set([])">Cancelar</button>
              </div>
            </div>
          }
        } @else {
          <p class="muted">Carregando…</p>
        }
      </section>

      <section class="panel">
        <h3>Próximos eventos (24 h){{ focusCurrency() ? ' · só ' + focusCurrency() : '' }}
          @if (focusCurrency()) { <tip text="O ouro é cotado em dólar e a regra dele vive das divulgações dos EUA: aqui só aparecem os eventos em USD. Os outros mercados mostram todas as moedas." /> }</h3>
        <div class="table-wrap"><table>
          <tr><th>Quando (UTC)</th><th>Moeda</th><th>Evento</th><th class="right">Previsto <tip text="O que o mercado espera (consenso). O preço reage à SURPRESA: a diferença entre o atual e o previsto." /></th><th class="right">Atual <tip text="O valor divulgado. Aparece segundos depois do horário." /></th></tr>
          @for (e of events(); track e.value_id) {
            <tr [class.muted]="isPast(e)">
              <td class="nowrap mono small" [brt]="e.scheduled_at">{{ e.scheduled_at | date: 'dd/MM HH:mm' : 'UTC' }}</td>
              <td><span class="badge" [class.high]="e.importance === 'HIGH'">{{ e.currency }}</span></td>
              <td>{{ e.name }}</td>
              <td class="right mono">{{ e.forecast ?? '—' }}</td>
              <td class="right mono">{{ e.actual ?? '—' }}</td>
            </tr>
          } @empty {
            <tr><td colspan="5" class="muted">Nenhum evento de importância média ou alta nas próximas 24 h.</td></tr>
          }
        </table></div>
      </section>
    </div>

    <section class="panel" style="margin-top:16px">
      <h3>Últimas notícias dos bancos centrais{{ focusCurrency() ? ' · só ' + focusCurrency() + ' (Fed)' : '' }}</h3>
      <div class="jev-box">
        <span class="small muted">Documentos novos só ganham a leitura do Jev depois de avaliados. Primeiro veja o plano (quantos
          trechos e quanto custa, sem chamar o Jev); depois avalie com teto de US$ 0,10 <tip key="jev" />.</span>
        <jobs-panel [names]="['jev-plano', 'jev-avaliar']" />
      </div>
      <div class="table-wrap"><table>
        <tr><th>Visto em (UTC)</th><th>Emissor</th><th>Título</th><th>Leitura do Jev <tip key="jev" /></th></tr>
        @for (n of news(); track n.id) {
          <tr>
            <td class="nowrap mono small" [brt]="n.first_seen_at">{{ n.first_seen_at | date: 'dd/MM HH:mm' : 'UTC' }}</td>
            <td class="nowrap">{{ n.issuer }} <span class="muted small">{{ n.currency }}</span></td>
            <td><a [href]="n.url" target="_blank" rel="noopener">{{ n.title }}</a></td>
            <td class="nowrap">
              @if (n.jev; as j) {
                <span class="badge" [class.buy]="j.stance > 0.15" [class.sell]="j.stance < -0.15">
                  {{ j.stance > 0.15 ? 'hawkish' : j.stance < -0.15 ? 'dovish' : 'neutro' }}
                  {{ j.stance | number: '1.2-2' }}</span>
                <span class="muted small"> relev. {{ j.relevance | number: '1.1-1' }}</span>
              } @else {
                <span class="muted small">não avaliado</span>
              }
            </td>
          </tr>
        } @empty {
          <tr><td colspan="4" class="muted">Sem documentos.</td></tr>
        }
      </table></div>
    </section>
  `,
  styles: `
    .auto.on { border-color: var(--up); }
    .autobar { display: flex; gap: 10px; align-items: center; flex-wrap: wrap; margin-bottom: 8px; }
    .autobar input { width: 70px; }
    .trade-bar { display: flex; gap: 10px; align-items: center; flex-wrap: wrap; margin: 6px 0 10px; }
    .trade-bar label { display: flex; align-items: center; gap: 6px; }
    .trade-bar input { width: 80px; font: inherit; padding: 4px 8px; border-radius: 6px; border: 1px solid var(--border);
      background: var(--panel-2); color: var(--text); }
    .jev-box { margin: 4px 0 12px; display: grid; gap: 6px; }
    .alert { display: flex; gap: 14px; align-items: center; justify-content: space-between; flex-wrap: wrap;
      padding: 12px 14px; margin-bottom: 14px; border-radius: 10px; border: 2px solid var(--warn); background: var(--panel-2); }
    .alert.buy { border-color: var(--up); }
    .alert.sell { border-color: var(--down); }
    .alert b { font-size: 16px; }
    .rule { margin: 0 0 8px; padding: 6px 10px; border-left: 3px solid var(--warn); background: var(--panel-2); border-radius: 4px; }
    .plan { margin-top: 12px; padding: 12px; border: 1px solid var(--warn); border-radius: 8px; background: var(--panel-2); }
    tr.blocked td { opacity: .6; }
  `,
})
export class Hoje implements OnInit, OnDestroy {
  private api = inject(Api);
  protected marketState = inject(MarketState);
  private http = inject(HttpClient);
  protected names = MODEL_NAMES;
  protected latest = signal<LatestPredictions | null>(null);
  protected events = signal<CalendarItem[]>([]);
  protected news = signal<NewsItem[]>([]);
  protected signals = computed(() => (this.latest()?.predictions ?? []).filter((p) => p.signal !== 'NO_TRADE'));
  /** O modelo da tabela principal: o da regra do mercado (ouro: B a 15 min) ou Eventos + Preço a 60 min. */
  protected mainSpec = computed(() => {
    const r = this.latest()?.rule;
    return r ? { model: r.model, horizon: r.horizon } : { model: 'B', horizon: 60 };
  });
  protected mainModel = computed(() =>
    (this.latest()?.predictions ?? []).filter((p) => p.model === this.mainSpec().model && p.horizonMin === this.mainSpec().horizon));
  protected ruleText = computed(() => {
    const r = this.latest()?.rule;
    if (!r) return null;
    return `Regra deste mercado: ${MODEL_NAMES[r.model] ?? r.model} a ${r.horizon} min, P ≥ ${Math.round(r.minProb * 100)}% e diferença ≥ ${Math.round(r.minMargin * 100)} pontos`
      + (r.eventsOnly ? ', só no momento de uma divulgação (evento + 2 min).' : '.');
  });
  /** Sinal da regra (ou do gate 4) com no máximo 10 min: é o que vale operar agora. */
  protected fresh = computed(() => {
    const spec = this.mainSpec();
    return this.signals().filter((p) => p.model === spec.model && p.horizonMin === spec.horizon && this.ageMin(p) <= 10)
      .sort((a, b) => b.momentUtc.localeCompare(a.momentUtc))[0] ?? null;
  });
  private timer?: ReturnType<typeof setInterval>;
  private slowTimer?: ReturnType<typeof setInterval>;

  // ------------------------------------------------------------------ operar pela previsão
  protected lot = signal(0.01);
  /** Sinal automático (GET /api/auto-signal). */
  protected auto = signal<any>(null);
  protected autoLot = signal(0.01);
  protected autoEntries = computed(() => (this.auto()?.entries ?? []).filter((e: any) => e.market === this.marketState.market()));
  protected busy = signal(false);
  protected plan = signal<{ symbol: string; side: 'BUY' | 'SELL'; preview: OrderPreview }[]>([]);
  protected results = signal<Record<string, { ok: boolean; text: string }>>({});
  protected okCount = computed(() => this.plan().filter((x) => x.preview.ok).length);
  protected okRisk = computed(() => this.plan().filter((x) => x.preview.ok).reduce((s, x) => s + (x.preview.riskUsd ?? 0), 0));

  constructor() {
    effect(() => { this.marketState.market(); this.plan.set([]); this.results.set({}); this.load(); this.loadSlow(); });
    this.api.tradeState().subscribe({ next: (s) => this.lot.set(s.lot), error: () => {} });
  }

  private loadAuto(): void {
    this.http.get<any>('/api/auto-signal').subscribe({ next: (a) => this.auto.set(a), error: () => this.auto.set(null) });
  }

  protected startAuto(): void {
    const a = this.auto();
    const real = a?.mode === 'LIVE';
    const market = this.marketState.market();
    const text = `Ligar o sinal automático em ${market} com lote ${this.autoLot()}?\nCada sinal da regra vira ordem sozinho (todas as travas valem).`;
    if (real) {
      if (prompt(`${text}\n\nCONTA REAL: digite LIVE para confirmar.`) !== 'LIVE') return;
    } else if (!confirm(text)) return;
    this.http.post<any>(`/api/auto-signal/start?market=${market}&lot=${this.autoLot()}`, {}).subscribe({
      next: () => this.loadAuto(),
      error: (e) => alert(e?.error?.message ?? 'não consegui ligar'),
    });
  }

  protected stopAuto(): void {
    this.http.post<any>(`/api/auto-signal/stop?market=${this.marketState.market()}`, {}).subscribe(() => this.loadAuto());
  }

  protected autoStatus(s: string): string {
    return ({ SENT: 'enviada', REFUSED: 'recusada', LATE: 'atrasado', SENDING: 'conferindo' } as Record<string, string>)[s] ?? s;
  }
  protected ageMin(p: Prediction): number {
    return Math.max(0, Math.floor((Date.now() - new Date(p.momentUtc).getTime()) / 60_000));
  }

  // o aviso (bipe e notificação) é do vigia global de sinais (signal-watch.ts), em qualquer página


  protected side(p: Prediction): 'BUY' | 'SELL' {
    return p.pUp > p.pDown ? 'BUY' : 'SELL';
  }

  protected strength(p: Prediction): number {
    return Math.abs(p.pUp - p.pDown) * 100;
  }

  /** Por que a previsão é fraca (null se passa no gate 4 ou na regra do mercado). */
  protected weak(p: Prediction): string | null {
    if (p.signal !== 'NO_TRADE') return null;
    if (p.pFlat >= p.pUp && p.pFlat >= p.pDown) return 'LATERAL é o mais provável: o modelo espera pouco movimento';
    const r = this.latest()?.rule;
    if (r && r.eventsOnly && p.kind !== 'EVENT') return 'a regra deste mercado só vale no momento de uma divulgação';
    return r ? `não passa na regra do mercado (P ≥ ${Math.round(r.minProb * 100)}% e diferença ≥ ${Math.round(r.minMargin * 100)} pontos)`
      : 'não passa no gate 4 (P ≥ 60% e diferença ≥ 35 pontos)';
  }

  protected failed(pv: OrderPreview): string {
    return pv.checks.filter((c) => !c.ok).map((c) => c.gate).join(', ');
  }

  protected setLot(ev: Event): void {
    const v = Number((ev.target as HTMLInputElement).value);
    if (v > 0) { this.lot.set(Math.round(v * 100) / 100); this.plan.set([]); }
  }

  protected previewOne(p: Prediction): void {
    this.previewList([p]);
  }

  protected previewAll(): void {
    this.previewList(this.mainModel());
  }

  /** Calcula cada ordem (sem enviar) e abre o quadro de confirmação. */
  private previewList(preds: Prediction[]): void {
    if (!preds.length) return;
    this.busy.set(true);
    const calls = preds.map((p) => this.api.preview(p.symbol, this.side(p), this.lot()).pipe(
      map((preview) => ({ symbol: p.symbol, side: this.side(p), preview })),
      catchError(() => of(null))));
    forkJoin(calls).subscribe((list) => {
      this.plan.set(list.filter((x): x is { symbol: string; side: 'BUY' | 'SELL'; preview: OrderPreview } => x !== null));
      this.busy.set(false);
    });
  }

  /** Envia em sequência só as que passaram; cada envio confere as travas de novo. */
  protected sendPlan(): void {
    const items = this.plan().filter((x) => x.preview.ok);
    const text = items.map((x) => `${x.side === 'BUY' ? 'COMPRAR' : 'VENDER'} ${this.lot()} ${x.symbol} · perda máx. US$ ${x.preview.riskUsd?.toFixed(2)}`).join('\n');
    if (!confirm(`Enviar ${items.length} ordem(ns)?\n\n${text}\n\nPerda máxima somada: US$ ${this.okRisk().toFixed(2)}`)) return;
    this.busy.set(true);
    from(items).pipe(concatMap((x) => this.api.submit(x.symbol, x.side, this.lot()).pipe(
      map((r) => ({ x, ok: !!r.orderId, text: r.orderId ? `ordem #${r.orderId} ${r.status === 'SHADOW' ? '(SHADOW: só registrada)' : 'enviada'}` : 'recusada: ' + this.failed(r.preview) })),
      catchError((e) => of({ x, ok: false, text: 'recusada: ' + (e?.error?.preview ? this.failed(e.error.preview) : (e?.error?.message ?? 'erro')) })))))
      .subscribe({
        next: (r) => this.results.update((m) => ({ ...m, [r.x.symbol]: { ok: r.ok, text: r.text } })),
        complete: () => { this.busy.set(false); this.plan.set([]); },
      });
  }

  ngOnInit(): void {
    this.timer = setInterval(() => this.load(), 20_000);   // previsões: o sinal de 15 min não pode esperar 1 min
    this.slowTimer = setInterval(() => this.loadSlow(), 60_000);
  }

  ngOnDestroy(): void {
    clearInterval(this.timer);
    clearInterval(this.slowTimer);
  }

  protected isPast(e: CalendarItem): boolean {
    return new Date(e.scheduled_at).getTime() < Date.now();
  }

  private load(): void {
    this.api.latest(this.marketState.market()).subscribe({ next: (l) => this.latest.set(l), error: () => this.latest.set(null) });
    this.loadAuto();
  }

  /** Ouro, bitcoin e energia são cotados em USD: o calendário e as notícias que importam são os dos EUA. */
  protected focusCurrency = computed(() => ['metals', 'crypto', 'energy'].includes(this.marketState.market()) ? 'USD' : null);

  private loadSlow(): void {
    this.api.upcoming(24, this.focusCurrency()).subscribe({ next: (e) => this.events.set(e), error: () => this.events.set([]) });
    this.api.news(25, this.focusCurrency()).subscribe({ next: (n) => this.news.set(n), error: () => this.news.set([]) });
  }
}
