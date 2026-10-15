import { Component, OnDestroy, OnInit, WritableSignal, computed, effect, inject, signal } from '@angular/core';
import { DatePipe, DecimalPipe, SlicePipe } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { Api, BacktestResult, LatestPredictions, MODEL_NAMES, MODEL_ORDER, Prediction, RunResult, Score } from '../api';
import { ProbBar } from './prob-bar';
import { Tip } from './tip';
import { Brt } from '../brt';
import { MarketState } from '../market';
import { MyTrades } from './my-trades';

@Component({
  selector: 'page-previsoes',
  imports: [DatePipe, DecimalPipe, SlicePipe, FormsModule, ProbBar, Tip, MyTrades, Brt],
  template: `
    <div class="head">
      <h1>Previsões ao vivo</h1>
      <button class="primary" (click)="runNow()" [disabled]="running()">{{ running() ? 'Prevendo…' : 'Prever agora' }}</button>
    </div>
    @if (run(); as r) {
      <p class="small" [class.muted]="!r.note">
        @if (r.note) { <span class="badge warn">{{ r.note }}</span> }
        @else { {{ r.rows }} previsões às <span [brt]="r.at">{{ r.at | date: 'HH:mm' : 'UTC' }} UTC</span> para {{ r.symbols.length }} pares. }
      </p>
    }
    @if (latest(); as l) {
      <p class="muted small">
        Modelos em produção: versão {{ l.model_version ?? 'nenhuma' }} (treino até {{ l.trained_until | slice: 0 : 10 }}).
        Previsões automáticas na hora cheia e 2 min depois de eventos de importância média/alta (seg a sex).
        @if (l.age_minutes !== null && l.age_minutes > 90) { <span class="badge warn">última previsão há {{ l.age_minutes }} min</span> }
      </p>
    }

    <section class="panel">
      <div class="table-wrap"><table>
        <tr>
          <th rowspan="2">Par</th><th rowspan="2">Momento (UTC)</th>
          @for (m of models; track m) { <th colspan="2" class="group">{{ names[m] }} <tip [key]="'modelo' + m" /></th> }
        </tr>
        <tr>
          @for (m of models; track m) { <th>15 min <tip key="horizonte" /></th><th>60 min</th> }
        </tr>
        @for (s of symbols(); track s) {
          <tr (click)="select(s)" class="row" [class.sel]="s === selected()">
            <td class="mono">{{ s }}</td>
            <td class="small muted nowrap" [brt]="moment(s)">{{ moment(s) | date: 'dd/MM HH:mm' : 'UTC' }}</td>
            @for (m of models; track m) {
              @for (h of horizons; track h) {
                <td>
                  @if (cell(s, m, h); as p) {
                    <prob-bar [down]="p.pDown" [flat]="p.pFlat" [up]="p.pUp" />
                    @if (p.signal !== 'NO_TRADE') {
                      <span class="badge" [class.buy]="p.signal === 'BUY'" [class.sell]="p.signal === 'SELL'">
                        {{ p.signal === 'BUY' ? 'COMPRA' : 'VENDA' }}</span>
                    }
                  } @else { <span class="muted">—</span> }
                </td>
              }
            }
          </tr>
        } @empty {
          <tr><td [attr.colspan]="2 + models.length * 2" class="muted">Sem previsões ainda.</td></tr>
        }
      </table></div>
      <p class="muted small">Barra: queda · lateral · alta (em %). Selo de COMPRA/VENDA só quando o gate 4 passa
        (P ≥ 60% e margem ≥ 35% sobre o lado oposto). Clique num par para ver o histórico.</p>
    </section>

    <div class="grid two" style="margin-top:16px">
      <section class="panel">
        <my-trades [days]="30" [compact]="true" />
        <h3 style="margin-top:14px">Placar dos modelos (últimos 90 dias) <tip text="O resultado REAL das previsões automáticas, depois do horizonte. É a única evidência nova daqui em diante." /></h3>
        <div class="table-wrap"><table>
          <tr><th>Modelo</th><th>Horizonte</th><th class="right">Resolvidas <tip key="resolvidas" /></th><th class="right">Acerto da classe <tip key="acertoClasse" /></th>
            <th class="right">Sinais <tip key="gate4" /></th><th class="right">E[R] <tip key="er" /></th><th class="right">Acerto <tip key="acerto" /></th></tr>
          @for (s of score(); track s.model + s.horizonMin) {
            <tr>
              <td>{{ names[s.model] ?? s.model }}</td>
              <td>{{ s.horizonMin }} min</td>
              <td class="right mono">{{ s.resolved }}</td>
              <td class="right mono">{{ s.resolved ? (100 * s.directional / s.resolved | number: '1.0-0') + '%' : '—' }}</td>
              <td class="right mono">{{ s.n }}</td>
              <td class="right mono" [class.up]="s.n && s.sumR > 0" [class.down]="s.n && s.sumR < 0">
                {{ s.n ? (s.sumR / s.n | number: '1.2-2') : '—' }}</td>
              <td class="right mono">{{ s.n ? (100 * s.hits / s.n | number: '1.0-0') + '%' : '—' }}</td>
            </tr>
          } @empty {
            <tr><td colspan="7" class="muted">Nenhuma previsão resolvida ainda (o resultado sai depois do horizonte).</td></tr>
          }
        </table></div>
        <p class="muted small">"Acerto da classe": a classe mais provável bateu com o que aconteceu. E[R]: média por
          operação com o stop e o alvo do mercado (página Risco) e saída no fim do horizonte, já com o spread. Previsões manuais ficam fora.</p>
      </section>

      <section class="panel">
        <h3>Histórico {{ selected() ?? '' }} (48 h)</h3>
        <div class="hist-bar">
          <label class="small"><input type="checkbox" [ngModel]="withFlat()" (ngModelChange)="withFlat.set($event)" />
            considerar LATERAL <tip text="Desmarcado: compara só a direção (ALTA se P(alta) > P(queda), senão QUEDA) com o que aconteceu, e ignora os momentos em que o preço ficou LATERAL. Marcado: compara a classe mais provável das três (inclusive LATERAL) com o resultado, em todos os momentos resolvidos." /></label>
        </div>
        <div class="hist-bar">
          <span class="small muted">Previsto <tip text="Mostra só as previsões cuja direção mais provável é esta (P(alta) > P(queda) = alta). Nenhum marcado = todas." /></span>
          <button class="chip" [class.on]="dirOn('ALTA')" (click)="toggle(dirFilter, 'ALTA')">P(alta)</button>
          <button class="chip" [class.on]="dirOn('QUEDA')" (click)="toggle(dirFilter, 'QUEDA')">P(queda)</button>
          <label class="small">P mín. <input class="pmin" type="number" min="0" max="100" step="5" [ngModel]="minProb()" (ngModelChange)="minProb.set(+$event || 0)" />%
            <tip text="Só as previsões em que a probabilidade do lado previsto (alta ou queda) é pelo menos isto." /></label>
          <span class="sep"></span>
          <span class="small muted">Horizonte</span>
          @for (h of horizons; track h) { <button class="chip" [class.on]="hFilter().has(h)" (click)="toggle(hFilter, h)">{{ h }} min</button> }
          <span class="sep"></span>
          <span class="small muted">Modelo</span>
          @for (m of models; track m) { <button class="chip" [class.on]="mFilter().has(m)" (click)="toggle(mFilter, m)" [title]="names[m] ?? m">{{ m }} · {{ names[m] }}</button> }
        </div>
        <div class="hist-bar">
          @if (tally(); as t) {
            <span class="small"><span class="pill hit">{{ t.hits }} acerto(s)</span> <span class="pill miss">{{ t.misses }} erro(s)</span>
              @if (t.hits + t.misses) { · {{ 100 * t.hits / (t.hits + t.misses) | number: '1.0-0' }}% }
              @if (!withFlat() && t.flat) { <span class="muted"> · {{ t.flat }} LATERAL fora da conta</span> }
              @if (t.pending) { <span class="muted"> · {{ t.pending }} aguardando</span> }</span>
          }
        </div>
        <div class="table-wrap"><table>
          <tr><th>Momento</th><th>Modelo</th><th>h <tip key="horizonte" /></th><th>Probabilidades <tip key="probabilidades" /></th><th>Sinal <tip key="gate4" /></th><th>Resultado <tip text="O que aconteceu de fato: ALTA ou QUEDA se andou mais de meio ATR já pagando o spread; senão LATERAL. Linha verde = a previsão acertou; vermelha = errou; sem cor = fora da conta (aguardando, ou LATERAL sem a opção marcada)." /></th></tr>
          @for (p of shown(); track p.id) {
            <tr [class.hit]="verdict(p) === true" [class.miss]="verdict(p) === false" [title]="'previsto: ' + (guess(p) ?? '—')">
              <td class="small mono nowrap" [brt]="p.momentUtc">{{ p.momentUtc | date: 'dd/MM HH:mm' : 'UTC' }}</td>
              <td class="small">{{ names[p.model] ?? p.model }}</td>
              <td class="small">{{ p.horizonMin }}</td>
              <td><prob-bar [down]="p.pDown" [flat]="p.pFlat" [up]="p.pUp" /></td>
              <td class="small">{{ p.signal === 'NO_TRADE' ? '—' : p.signal === 'BUY' ? 'COMPRA' : 'VENDA' }}</td>
              <td class="small">{{ p.label ?? 'aguardando' }}</td>
            </tr>
          } @empty {
            <tr><td colspan="6" class="muted">{{ selected() ? (history().length ? 'Nenhuma previsão com estes filtros.' : 'Sem histórico.') : 'Selecione um par na tabela.' }}</td></tr>
          }
        </table></div>
      </section>
    </div>

    <section class="panel bt" style="margin-top:16px">
      <h3>Backtest das previsões <tip text="E se cada previsão já resolvida do período tivesse virado uma ordem no lado mais provável (compra se P(alta) > P(queda), senão venda), com o stop, o alvo e a saída no horizonte do placar, já com o spread? O resultado em US$ usa o lote escolhido (ou o mínimo do símbolo) e a perda no stop de hoje; com o stop em US$ ligado, 1 R = esse valor." /></h3>
      <div class="row2">
        <label class="small">Pares <select [(ngModel)]="bt.scope"><option value="all">todos do mercado</option>
          <option value="one" [disabled]="!selected()">{{ selected() ?? 'selecione um par' }}</option></select></label>
        <label class="small">Período <select [(ngModel)]="bt.period">@for (p of periods; track p.v) { <option [value]="p.v">{{ p.l }}</option> }</select></label>
        <label class="small">Horizonte <select [(ngModel)]="bt.horizon"><option [ngValue]="15">15 min</option><option [ngValue]="60">60 min</option></select></label>
        <label class="small">Modelo <select [(ngModel)]="bt.model">@for (m of models; track m) { <option [value]="m">{{ m }} · {{ names[m] }}</option> }</select></label>
        <label class="small">Lote <input type="number" min="0.01" step="0.01" [(ngModel)]="bt.lot" /></label>
        <label class="small">Direção <select [(ngModel)]="bt.side"><option value="all">todas</option><option value="BUY">só P(alta)</option><option value="SELL">só P(queda)</option></select></label>
        <label class="small">P mín. <input type="number" min="0" max="100" step="5" [(ngModel)]="bt.minProb" />%</label>
        <label class="small"><input type="checkbox" [(ngModel)]="bt.skipFlat" style="width:auto" /> pular LATERAL <tip text="Marcado: quando LATERAL é o mais provável não há ordem (como a roleta de tendência). Desmarcado: opera essas também, pelo lado mais provável entre alta e queda." /></label>
        <button class="primary" (click)="runBacktest()" [disabled]="btBusy()">{{ btBusy() ? 'Calculando…' : 'Rodar backtest' }}</button>
      </div>
      @if (btError(); as e) { <p class="down small">{{ e }}</p> }
      @if (btResult(); as r) {
        <div class="kpis">
          <div class="kpi"><span class="small muted">operações</span><b>{{ r.trades }}</b></div>
          <div class="kpi hit"><span class="small">acertos (lucro)</span><b>{{ r.wins }}</b></div>
          <div class="kpi miss"><span class="small">derrotas</span><b>{{ r.losses }}</b></div>
          <div class="kpi" [class.hit]="r.usd > 0" [class.miss]="r.usd < 0"><span class="small">resultado</span><b>US$ {{ r.usd | number: '1.2-2' }}</b></div>
          <div class="kpi" [class.hit]="(r.avgR ?? 0) > 0" [class.miss]="(r.avgR ?? 0) < 0"><span class="small">E[R] por operação</span><b>{{ r.avgR === null ? '—' : (r.avgR | number: '1.2-2') }}</b></div>
          <div class="kpi"><span class="small muted">acerto</span><b>{{ r.trades ? (100 * r.wins / r.trades | number: '1.0-0') + '%' : '—' }}</b></div>
        </div>
        <p class="small muted">Desde <span [brt]="r.from">{{ r.from | date: 'dd/MM HH:mm' : 'UTC' }} UTC</span>
          @if (r.skippedFlat) { · {{ r.skippedFlat }} com LATERAL mais provável puladas }
          @if (r.filtered) { · {{ r.filtered }} fora dos filtros }
          @if (r.pending) { · {{ r.pending }} ainda aguardando o resultado (o horizonte não terminou) }
          @if (r.moneyStop) { · 1 R = o stop em US$ da página Risco }</p>
        @if (r.bySymbol.length > 1) {
          <div class="table-wrap"><table>
            <tr><th>Par</th><th class="right">Operações</th><th class="right">Acertos</th><th class="right">Derrotas</th><th class="right">Soma R</th><th class="right">US$</th></tr>
            @for (l of r.bySymbol; track l.symbol) {
              <tr [class.hit]="l.usd > 0" [class.miss]="l.usd < 0">
                <td class="mono">{{ l.symbol }}</td><td class="right mono">{{ l.trades }}</td><td class="right mono">{{ l.wins }}</td>
                <td class="right mono">{{ l.losses }}</td><td class="right mono">{{ l.sumR | number: '1.2-2' }}</td>
                <td class="right mono">{{ l.usd | number: '1.2-2' }}</td></tr>
            }
          </table></div>
        }
        <div class="table-wrap bt-list"><table>
          <tr><th>Momento</th><th>Par</th><th>Lado</th><th class="right">P</th><th>Resultado</th><th class="right">R</th><th class="right">US$</th></tr>
          @for (t of r.list; track $index) {
            <tr [class.hit]="t.r > 0" [class.miss]="t.r <= 0">
              <td class="small mono nowrap" [brt]="t.moment">{{ t.moment | date: 'dd/MM HH:mm' : 'UTC' }}</td>
              <td class="mono">{{ t.symbol }}</td>
              <td class="small">{{ t.side === 'BUY' ? 'COMPRA' : 'VENDA' }}</td>
              <td class="right mono small">{{ t.pSide * 100 | number: '1.0-0' }}%</td>
              <td class="small">{{ t.label }}</td>
              <td class="right mono">{{ t.r | number: '1.2-2' }}</td>
              <td class="right mono">{{ t.usd === null ? '—' : (t.usd | number: '1.2-2') }}</td>
            </tr>
          } @empty {
            <tr><td colspan="7" class="muted">Nenhuma previsão resolvida no período com estes filtros.</td></tr>
          }
        </table></div>
      }
    </section>
  `,
  styles: `
    .head { display: flex; align-items: center; justify-content: space-between; gap: 12px; flex-wrap: wrap; }
    .group { text-align: center; border-left: 1px solid var(--border); }
    .row { cursor: pointer; }
    .row:hover td { background: var(--panel-2); }
    .row.sel td { background: var(--panel-2); }
    .badge { margin-top: 2px; }
    .hist-bar { display: flex; gap: 14px; align-items: center; flex-wrap: wrap; margin: 4px 0 8px; }
    .hist-bar label { display: flex; align-items: center; gap: 6px; }
    tr.hit td { background: var(--up-bg); }
    tr.miss td { background: var(--down-bg); }
    .pill { padding: 1px 8px; border-radius: 999px; font-weight: 600; }
    .pill.hit { background: var(--up-bg); color: var(--up); }
    .pill.miss { background: var(--down-bg); color: var(--down); }
    .chip { padding: 2px 10px; border-radius: 999px; font-size: 12px; }
    .chip.on { background: var(--accent); border-color: var(--accent); color: #fff; }
    .sep { width: 8px; }
    input.pmin, .bt input, .bt select { width: 64px; font: inherit; padding: 2px 6px; border-radius: 6px; border: 1px solid var(--border);
      background: var(--panel-2); color: var(--text); }
    .bt select { width: auto; }
    .bt .row2 { display: flex; gap: 10px; align-items: center; flex-wrap: wrap; margin-bottom: 10px; }
    .bt .row2 label { display: flex; align-items: center; gap: 6px; }
    .kpis { display: flex; gap: 12px; flex-wrap: wrap; margin: 6px 0 12px; }
    .kpi { padding: 8px 14px; border-radius: 10px; background: var(--panel-2); display: grid; }
    .kpi b { font-size: 20px; }
    .kpi.hit { background: var(--up-bg); color: var(--up); }
    .kpi.miss { background: var(--down-bg); color: var(--down); }
    .bt-list { max-height: 360px; overflow-y: auto; }
  `,
})
export class Previsoes implements OnInit, OnDestroy {
  private api = inject(Api);
  private marketState = inject(MarketState);
  protected names = MODEL_NAMES;
  protected models = MODEL_ORDER;
  protected horizons = [15, 60];
  protected latest = signal<LatestPredictions | null>(null);
  protected score = signal<Score[]>([]);
  protected history = signal<Prediction[]>([]);
  protected selected = signal<string | null>(null);
  protected running = signal(false);
  protected run = signal<RunResult | null>(null);
  protected symbols = computed(() => [...new Set((this.latest()?.predictions ?? []).map((p) => p.symbol))].sort());
  private byKey = computed(() => {
    const m = new Map<string, Prediction>();
    for (const p of this.latest()?.predictions ?? []) m.set(`${p.symbol}|${p.model}|${p.horizonMin}`, p);
    return m;
  });
  private timer?: ReturnType<typeof setInterval>;

  /** Histórico: conta LATERAL como classe (marcado) ou só a direção alta × queda (desmarcado). */
  protected withFlat = signal(false);
  /** Filtros do histórico (vazio = todos). */
  protected dirFilter = signal<Set<string>>(new Set());
  protected hFilter = signal<Set<number>>(new Set());
  protected mFilter = signal<Set<string>>(new Set());
  protected minProb = signal(0);
  protected shown = computed(() => {
    const dir = this.dirFilter(), hs = this.hFilter(), ms = this.mFilter(), min = this.minProb() / 100;
    return this.history().filter((p) => {
      const d = p.pUp > p.pDown ? 'ALTA' : p.pDown > p.pUp ? 'QUEDA' : null;
      if (dir.size && (d === null || !dir.has(d))) return false;
      if (Math.max(p.pUp, p.pDown) < min) return false;
      if (hs.size && !hs.has(p.horizonMin)) return false;
      return !ms.size || ms.has(p.model);
    });
  });
  protected tally = computed(() => {
    const t = { hits: 0, misses: 0, flat: 0, pending: 0 };
    this.withFlat();
    for (const p of this.shown()) {
      if (!p.label) { t.pending++; continue; }
      const v = this.verdict(p);
      if (v === true) t.hits++;
      else if (v === false) t.misses++;
      else t.flat++;
    }
    return this.shown().length ? t : null;
  });

  protected dirOn(d: string): boolean {
    return this.dirFilter().has(d);
  }

  protected toggle<T>(s: WritableSignal<Set<T>>, v: T): void {
    const n = new Set(s());
    if (n.has(v)) n.delete(v); else n.add(v);
    s.set(n);
  }

  // ------------------------------------------------------------------ backtest
  protected periods = [{ v: '1h', l: 'última hora' }, { v: 'today', l: 'hoje (UTC)' }, { v: '24h', l: '24 h' },
    { v: '48h', l: '48 h' }, { v: '7d', l: '7 dias' }, { v: '30d', l: '30 dias' }];
  protected bt = { scope: 'all', period: 'today', horizon: 60, model: 'B', lot: 0.01, side: 'all', minProb: 0, skipFlat: true };
  protected btResult = signal<BacktestResult | null>(null);
  protected btBusy = signal(false);
  protected btError = signal<string | null>(null);

  protected runBacktest(): void {
    this.btBusy.set(true);
    this.btError.set(null);
    this.api.backtest({ market: this.marketState.market(), symbol: this.bt.scope === 'one' ? this.selected() : null,
      period: this.bt.period, horizon: this.bt.horizon, model: this.bt.model, lot: this.bt.lot, side: this.bt.side,
      minProb: this.bt.minProb / 100, skipFlat: this.bt.skipFlat }).subscribe({
      next: (r) => { this.btResult.set(r); this.btBusy.set(false); },
      error: (e) => { this.btError.set(e?.error?.message ?? 'backtest indisponível (reinicie o servidor?)'); this.btBusy.set(false); },
    });
  }

  constructor() {
    effect(() => { this.marketState.market(); this.selected.set(null); this.history.set([]); this.load(); });
  }

  ngOnInit(): void {
    this.timer = setInterval(() => this.load(), 60_000);
  }

  ngOnDestroy(): void {
    clearInterval(this.timer);
  }

  protected cell(symbol: string, model: string, h: number): Prediction | undefined {
    return this.byKey().get(`${symbol}|${model}|${h}`);
  }

  /** O que a previsão apostou: a classe mais provável (com LATERAL) ou a direção mais provável (sem). */
  protected guess(p: Prediction): string | null {
    if (this.withFlat()) {
      const max = Math.max(p.pDown, p.pFlat, p.pUp);
      return max === p.pUp ? 'ALTA' : max === p.pDown ? 'QUEDA' : 'LATERAL';
    }
    return p.pUp > p.pDown ? 'ALTA' : p.pDown > p.pUp ? 'QUEDA' : null;
  }

  /** true = acertou, false = errou, null = fora da conta (aguardando, ou LATERAL sem a opção marcada). */
  protected verdict(p: Prediction): boolean | null {
    if (!p.label) return null;
    if (!this.withFlat() && p.label === 'LATERAL') return null;
    const g = this.guess(p);
    return g === null ? null : g === p.label;
  }

  protected moment(symbol: string): string | undefined {
    return (this.latest()?.predictions ?? []).find((p) => p.symbol === symbol)?.momentUtc;
  }

  protected select(symbol: string): void {
    this.selected.set(symbol);
    this.api.history(symbol, 48, this.marketState.market()).subscribe((h) => this.history.set(h));
  }

  protected runNow(): void {
    this.running.set(true);
    this.api.runNow().subscribe({
      next: (r) => { this.run.set(r); this.running.set(false); this.load(); },
      error: (e) => {
        this.run.set({ at: '', trigger: 'MANUAL', modelVersion: null, rows: 0, symbols: [], lastBarUtc: null,
          note: 'Falhou: ' + (e?.error?.message ?? e?.message ?? 'erro') });
        this.running.set(false);
      },
    });
  }

  private load(): void {
    this.api.latest(this.marketState.market()).subscribe({ next: (l) => this.latest.set(l), error: () => this.latest.set(null) });
    this.api.scoreboard(90, this.marketState.market()).subscribe({ next: (s) => this.score.set(s.models), error: () => this.score.set([]) });
    const s = this.selected();
    if (s) this.api.history(s, 48, this.marketState.market()).subscribe((h) => this.history.set(h));
  }
}
