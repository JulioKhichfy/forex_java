import { Component, OnDestroy, OnInit, computed, inject, signal } from '@angular/core';
import { DatePipe, DecimalPipe } from '@angular/common';
import { Api, MODEL_NAMES, RouletteEntry, RouletteState } from '../api';
import { MARKETS } from '../market';
import { Tip } from './tip';
import { Brt } from '../brt';

interface Form {
  horizon: number;
  model: string;
  lot: number | null;
  strategy: 'trend' | 'pullback';
  band: number;
}

/**
 * Pullback no LATERAL, teste fora da amostra de 14/10/2026 (previsões do walk-forward de abr–set/2026, modelo B,
 * até 10% da banda, stop 1,5 ATR, alvo 1,5 R, saída no horizonte, com spread): E[R] por operação.
 */
const PULLBACK_STUDY: Record<string, Record<number, number>> = {
  fx: { 15: -0.186, 60: -0.178 },
  indices: { 15: -0.138, 60: -0.168 },
  stocks: { 15: -0.266, 60: -0.271 },
  metals: { 15: 0.003, 60: 0.006 },
  crypto: { 15: -0.067, 60: -0.051 },
};

@Component({
  selector: 'page-roleta',
  imports: [DatePipe, DecimalPipe, Tip, Brt],
  template: `
    <div class="notice danger">
      <b>Roleta = ordens automáticas sem as travas de risco por ordem.</b> Com um mercado ligado, cada previsão nova vira
      uma ordem em cada símbolo e fecha sozinha no horizonte escolhido. <b>Tendência</b>: o lado mais provável, pulando
      LATERAL. <b>Pullback</b> (teste): só em LATERAL, contra a banda de Bollinger. Só param as entradas: o botão <b>Parar roleta russa</b>, o <b>BLOCK</b> da página Operar e o
      limite de <b>perda do dia/semana</b> (que para todas as roletas). Confira em cada mercado o que esta regra deu nas
      previsões já resolvidas antes de ligar.
      @if (state(); as s) {
        <div class="small">Modo <b>{{ s.mode }}</b> · perfil de perda {{ s.loss_profile.label }} (dia {{ s.loss_profile.maxDailyLossPct }}%,
          semana {{ s.loss_profile.maxWeeklyLossPct }}%) @if (s.blocked) { · <b class="down">BLOCK ligado: nenhuma ordem sai</b> }</div>
      }
    </div>

    <div class="stopbar">
      <button class="stop" (click)="stopAll()" [disabled]="busy() || running().length === 0">
        PARAR ROLETA RUSSA {{ running().length ? '(' + running().join(', ') + ')' : '' }}</button>
      <span class="small muted">Parar não fecha as posições já abertas: elas saem pelo tempo, pelo stop ou pelo alvo
        (ou feche na página Operar).</span>
    </div>

    @if (error()) { <p class="down">{{ error() }}</p> }

    <div class="cards">
      @for (m of markets; track m.code) {
        @let c = state()?.markets?.[m.code];
        @let f = form(m.code);
        @let strat = c?.on ? c!.strategy : f.strategy;
        @let hz = c?.on ? c!.horizon : f.horizon;
        @let sim = simulated(m.code, hz, c?.on ? c!.model : f.model);
        <section class="panel card" [class.on]="c?.on" [class.test]="strat === 'pullback'">
          <h3>{{ m.label }}
            @if (c?.on) { <span class="badge running">RODANDO</span> } @else { <span class="badge">parada</span> }
            @if (strat === 'pullback') { <span class="badge sell">PULLBACK · TESTE</span> }</h3>
          @if (c?.on) {
            <p class="small">Desde <span [brt]="c!.startedAt">{{ c!.startedAt | date: 'dd/MM HH:mm' : 'UTC' }} UTC</span> ·
              {{ names[c!.model] ?? c!.model }} · <b>{{ c!.horizon }} min</b> · lote {{ c!.lot ?? 'mínimo de cada símbolo' }}
              @if (c!.strategy === 'pullback') { · {{ bandLabel(c!.band) }} }</p>
            <button class="stop" (click)="stop(m.code)" [disabled]="busy()">Parar roleta russa</button>
          } @else {
            <div class="form">
              <label>Estratégia <tip text="Tendência: o lado mais provável, pulando quando LATERAL é o mais provável. Pullback (teste): só quando LATERAL é o mais provável, COMPRA na banda inferior de Bollinger e VENDE na superior." />
                <select [value]="f.strategy" (change)="patch(m.code, { strategy: $any($event.target).value })">
                  <option value="trend">Tendência</option>
                  <option value="pullback" [disabled]="!serverHasStrategy()">Pullback no LATERAL (teste){{ serverHasStrategy() ? '' : ' — reinicie o servidor' }}</option>
                </select>
              </label>
              @if (f.strategy === 'pullback') {
                <label>Banda <tip text="Quão perto da banda de Bollinger (20, 2, M15) o preço precisa estar. %B: 0 = banda inferior, 1 = superior. 'Até 10%' compra com %B ≤ 0,1 e vende com %B ≥ 0,9." />
                  <select [value]="'' + f.band" (change)="patch(m.code, { band: +$any($event.target).value })">
                    <option value="0">fora das bandas</option>
                    <option value="0.1">até 10% da banda</option>
                    <option value="0.2">até 20% da banda</option>
                  </select>
                </label>
              }
              <label>Horizonte <tip text="A ordem fecha sozinha depois deste tempo, se o stop ou o alvo não vierem antes. Usa a previsão do mesmo horizonte." />
                <span class="seg">
                  @for (h of [15, 60]; track h) {
                    <button [class.primary]="f.horizon === h" (click)="patch(m.code, { horizon: h })">{{ h }} min</button>
                  }
                </span>
              </label>
              <label>Modelo
                <select [value]="f.model" (change)="patch(m.code, { model: $any($event.target).value })">
                  @for (k of ['B', 'A', 'E']; track k) { <option [value]="k">{{ names[k] }}</option> }
                </select>
              </label>
              <label>Lote <tip text="Vazio = o lote mínimo de cada símbolo (0,01 no forex; índices e ações têm mínimo maior). O lote máximo da página Risco continua valendo." />
                <input type="number" min="0.01" step="0.01" placeholder="mínimo" [value]="f.lot ?? ''"
                  (input)="patch(m.code, { lot: +$any($event.target).value || null })" />
              </label>
            </div>
            <button class="primary" (click)="start(m.code, m.label)" [disabled]="busy() || !state()">Iniciar roleta</button>
            @if (c?.stopReason) { <p class="small muted">Parou <span [brt]="c!.stoppedAt">{{ c!.stoppedAt | date: 'dd/MM HH:mm' : 'UTC' }}</span>: {{ c!.stopReason }}</p> }
          }

          <div class="stats">
            @if (strat === 'pullback') {
              @let st = study(m.code, hz);
              <div class="test-box small">
                <b>Teste fora da amostra</b> (abr–set/2026, modelo Eventos + Preço, até 10% da banda, {{ hz }} min):
                <b [class.up]="(st ?? 0) > 0.02" [class.down]="(st ?? 0) < -0.02">E[R] {{ st === null ? '—' : ((st > 0 ? '+' : '') + (st | number: '1.2-2')) }}</b>
                por operação, já com o spread. Negativo em forex, índices, ações e bitcoin; zero no ouro. É um teste ao
                vivo da sua hipótese: acompanhe o resultado real abaixo.
              </div>
            } @else {
              <div class="small muted">Esta regra nas previsões já resolvidas ({{ days }} dias) <tip text="Cada previsão do modelo e horizonte escolhidos, no lado mais provável, pulando LATERAL, com o stop, o alvo, a saída por tempo e o spread do placar. É o que a roleta teria feito, sem slippage." /></div>
              @if (sim) {
                <div [class.up]="sim.avg_r > 0" [class.down]="sim.avg_r < 0">
                  <b>E[R] {{ sim.avg_r > 0 ? '+' : '' }}{{ sim.avg_r | number: '1.2-2' }}</b> por operação · {{ sim.n }} operações ·
                  soma {{ sim.sum_r | number: '1.0-0' }} R · com lucro {{ sim.hit_rate * 100 | number: '1.0-0' }}%
                </div>
              } @else { <div class="muted">nenhuma previsão resolvida fora de LATERAL</div> }
            }

            @for (row of strategiesOf(m.code, strat); track row) {
              <div class="small muted" style="margin-top:8px">Ordens da roleta · {{ strategyLabel(row) }} ({{ days }} dias)</div>
              @let n = count(m.code, row);
              <div class="small">enviadas {{ n.sent }} · puladas {{ n.skipped }} · recusadas {{ n.refused }}</div>
              @if (closed(m.code, row); as cl) {
                <div [class.up]="(cl.result_usd ?? 0) > 0" [class.down]="(cl.result_usd ?? 0) < 0">
                  fechadas {{ cl.trades }} · <b>US$ {{ (cl.result_usd ?? 0) | number: '1.2-2' }}</b>
                  @if (cl.avg_r !== null) { · E[R] {{ cl.avg_r | number: '1.2-2' }} } · com lucro {{ cl.wins }}
                </div>
              }
              @if (open(m.code, row); as op) {
                <div class="small">abertas agora {{ op.positions }} · perda se todas derem stop US$ {{ (op.risk_usd ?? 0) | number: '1.2-2' }} ·
                  resultado aberto <span [class.up]="(op.profit_usd ?? 0) > 0" [class.down]="(op.profit_usd ?? 0) < 0">US$ {{ (op.profit_usd ?? 0) | number: '1.2-2' }}</span></div>
              }
            }
          </div>
        </section>
      }
    </div>
    <section class="panel" style="margin-top:16px">
      <h3>Últimas entradas da roleta</h3>
      <div class="table-wrap"><table>
        <tr><th>Quando (UTC)</th><th>Mercado</th><th>Estratégia</th><th>Símbolo</th><th>Previsão</th><th>Lado</th><th>Situação</th><th>Detalhe</th></tr>
        @for (e of state()?.entries ?? []; track e.id) {
          <tr [class.muted]="e.status === 'SKIPPED'">
            <td class="nowrap mono small" [brt]="e.created_at">{{ e.created_at | date: 'dd/MM HH:mm:ss' : 'UTC' }}</td>
            <td class="small">{{ marketLabel(e.market) }}</td>
            <td class="small nowrap">{{ strategyLabel(e.strategy) }}
              @if (e.pct_b !== null) { <span class="muted"> %B {{ e.pct_b | number: '1.2-2' }}</span> }</td>
            <td class="mono">{{ e.symbol }}</td>
            <td class="small nowrap">{{ e.model }} · {{ e.horizon_min }} min · <span [brt]="e.moment_utc">{{ e.moment_utc | date: 'HH:mm' : 'UTC' }}</span></td>
            <td class="nowrap">
              @if (e.side) { <span class="badge" [class.buy]="e.side === 'BUY'" [class.sell]="e.side === 'SELL'">{{ e.side === 'BUY' ? 'COMPRA' : 'VENDA' }}</span> }
              @if (e.p_side !== null) { <span class="small muted"> {{ e.p_side * 100 | number: '1.0-0' }}%</span> }
            </td>
            <td class="nowrap"><span class="badge" [class.ok]="situation(e) === 'executada'" [class.high]="situation(e).startsWith('recus') || situation(e) === 'rejeitada'">{{ situation(e) }}</span></td>
            <td class="small">{{ e.order_message ?? e.message }}</td>
          </tr>
        } @empty {
          <tr><td colspan="8" class="muted">Nenhuma entrada ainda. Ligada, a roleta entra no próximo momento de decisão (hora cheia ou evento + 2 min).</td></tr>
        }
      </table></div>
    </section>
  `,
  styles: `
    .notice.danger { border-left-color: var(--down); background: var(--down-bg); }
    .stopbar { display: flex; gap: 12px; align-items: center; flex-wrap: wrap; margin-bottom: 16px; }
    button.stop { background: var(--down); border-color: var(--down); color: #fff; font-weight: 700; }
    button.stop:disabled { background: var(--panel-2); border-color: var(--border); color: var(--muted); }
    .cards { display: grid; gap: 16px; grid-template-columns: repeat(auto-fit, minmax(300px, 1fr)); }
    .card.on { border: 2px solid var(--down); }
    .badge.running { background: var(--down); color: #fff; }
    .form { display: grid; gap: 8px; margin: 8px 0 10px; }
    .form label { display: flex; gap: 8px; align-items: center; justify-content: space-between; }
    .form input, .form select { font: inherit; padding: 4px 8px; border-radius: 6px; border: 1px solid var(--border);
      background: var(--panel-2); color: var(--text); width: 180px; }
    .seg { display: inline-flex; gap: 4px; }
    .stats { margin-top: 12px; padding-top: 10px; border-top: 1px solid var(--border); display: grid; gap: 2px; }
    .card.test { background: linear-gradient(var(--down-bg), var(--down-bg)) top / 100% 4px no-repeat, var(--panel); }
    .test-box { padding: 8px 10px; border-left: 3px solid var(--down); background: var(--down-bg); border-radius: 4px; }
  `,
})
export class Roleta implements OnInit, OnDestroy {
  private api = inject(Api);
  protected names = MODEL_NAMES;
  protected markets = MARKETS;
  protected days = 30;
  protected state = signal<RouletteState | null>(null);
  protected busy = signal(false);
  protected error = signal<string | null>(null);
  private forms = signal<Record<string, Form>>({});
  private timer?: ReturnType<typeof setInterval>;

  /** Servidor com a roleta por estratégia (14/10/2026); o antigo ignoraria o pullback e ligaria a tendência. */
  protected serverHasStrategy = computed(() => Object.values(this.state()?.markets ?? {}).some((c) => c.strategy !== undefined));

  protected running = computed(() => Object.entries(this.state()?.markets ?? {}).filter(([, c]) => c.on)
    .map(([m]) => this.marketLabel(m)));

  ngOnInit(): void {
    this.load();
    this.timer = setInterval(() => this.load(), 10_000);
  }

  ngOnDestroy(): void {
    clearInterval(this.timer);
  }

  private load(): void {
    this.api.roulette(this.days).subscribe({
      next: (s) => { this.state.set(s); this.error.set(null); },
      error: (e) => this.error.set('Roleta indisponível: ' + (e?.error?.message ?? e?.message ?? 'erro')),
    });
  }

  protected form(market: string): Form {
    const c = this.state()?.markets?.[market];
    return this.forms()[market] ?? { horizon: c?.horizon ?? 60, model: c?.model ?? 'B', lot: c?.lot ?? null,
      strategy: c?.strategy ?? 'trend', band: c?.band ?? 0.1 };
  }

  protected patch(market: string, p: Partial<Form>): void {
    this.forms.update((f) => ({ ...f, [market]: { ...this.form(market), ...p } }));
  }

  protected simulated(market: string, horizon: number, model: string) {
    return this.state()?.simulated.find((s) => s.market === market && s.horizon_min === horizon && s.model === model) ?? null;
  }

  protected count(market: string, strategy: string) {
    return this.state()?.counts.find((c) => c.market === market && (c.strategy ?? 'trend') === strategy) ?? { sent: 0, skipped: 0, refused: 0 };
  }

  protected closed(market: string, strategy: string) {
    return this.state()?.closed.find((c) => c.market === market && (c.strategy ?? 'trend') === strategy) ?? null;
  }

  protected open(market: string, strategy: string) {
    return this.state()?.open.find((c) => c.market === market && (c.strategy ?? 'trend') === strategy) ?? null;
  }

  /** As estratégias com algum dado no mercado (placar separado), começando pela escolhida agora. */
  protected strategiesOf(market: string, current: string): string[] {
    const s = this.state();
    const seen = new Set<string>([current]);
    for (const r of [...(s?.counts ?? []), ...(s?.closed ?? []), ...(s?.open ?? [])]) {
      if (r.market === market) seen.add(r.strategy ?? 'trend');
    }
    return [...seen];
  }

  protected strategyLabel(s: string): string {
    return s === 'pullback' ? 'Pullback (teste)' : 'Tendência';
  }

  protected bandLabel(b: number): string {
    return b === 0 ? 'fora das bandas' : `até ${Math.round(b * 100)}% da banda`;
  }

  protected study(market: string, horizon: number): number | null {
    return PULLBACK_STUDY[market]?.[horizon] ?? null;
  }

  protected marketLabel(code: string): string {
    return MARKETS.find((m) => m.code === code)?.label ?? code;
  }

  protected situation(e: RouletteEntry): string {
    if (e.status === 'SKIPPED') return 'pulada';
    if (e.status === 'REFUSED') return 'recusada pelas travas';
    if (e.status === 'SENDING') return 'enviando';
    switch (e.order_status) {
      case 'FILLED': return 'executada';
      case 'PENDING': case 'SENT': return 'aguardando o EA';
      case 'REJECTED': return 'rejeitada';
      case 'EXPIRED': return 'venceu';
      case 'CANCELLED': return 'cancelada';
      case 'SHADOW': return 'só registrada (SHADOW)';
      default: return e.order_status ?? 'enviada';
    }
  }

  protected start(market: string, label: string): void {
    const s = this.state();
    if (!s) return;
    const f = this.form(market);
    const sim = this.simulated(market, f.horizon, f.model);
    const st = this.study(market, f.horizon);
    const simText = f.strategy === 'pullback'
      ? `PULLBACK (TESTE): só quando LATERAL é o mais provável; compra na banda inferior, vende na superior (${this.bandLabel(f.band)}).\n`
        + `No teste fora da amostra (abr–set/2026) deu E[R] ${st === null ? '—' : (st >= 0 ? '+' : '') + st.toFixed(2)} por operação.`
      : sim ? `Nas previsões já resolvidas, esta regra deu E[R] ${sim.avg_r >= 0 ? '+' : ''}${sim.avg_r.toFixed(2)} por operação `
        + `(${sim.n} operações, soma ${sim.sum_r.toFixed(0)} R).` : 'Ainda não há previsões resolvidas para esta regra.';
    const text = `Ligar a roleta de ${label} em ${s.mode}?\n\n${MODEL_NAMES[f.model] ?? f.model}, ${f.horizon} min, lote `
      + `${f.lot ?? 'mínimo de cada símbolo'}.\nCada previsão nova vira uma ordem por símbolo, sem as travas de risco por ordem.\n\n${simText}`;
    if (f.strategy === 'pullback' && !this.serverHasStrategy()) {
      this.error.set('O servidor em execução é anterior ao pullback: reinicie-o antes de ligar esta estratégia.');
      return;
    }
    if (!confirm(text)) return;
    if (s.mode === 'LIVE' && prompt('Conta REAL. Para confirmar, digite LIVE') !== 'LIVE') return;
    this.busy.set(true);
    this.api.rouletteStart(market, f.horizon, f.model, f.lot, f.strategy, f.band).subscribe({
      next: () => { this.busy.set(false); this.forms.update((x) => { const y = { ...x }; delete y[market]; return y; }); this.load(); },
      error: (e) => { this.busy.set(false); this.error.set(e?.error?.message ?? 'não consegui ligar a roleta'); },
    });
  }

  protected stop(market: string): void {
    this.busy.set(true);
    this.api.rouletteStop(market).subscribe({ next: () => { this.busy.set(false); this.load(); }, error: () => this.busy.set(false) });
  }

  protected stopAll(): void {
    this.busy.set(true);
    this.api.rouletteStop().subscribe({ next: () => { this.busy.set(false); this.load(); }, error: () => this.busy.set(false) });
  }
}
