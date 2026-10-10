import { Component, OnDestroy, OnInit, computed, inject, signal } from '@angular/core';
import { DatePipe, DecimalPipe } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { Api, LatestPredictions, MODEL_NAMES, MODEL_ORDER, OrderPreview, Prediction, Submit, TradeState } from '../api';
import { ProbBar } from './prob-bar';

@Component({
  selector: 'page-operar',
  imports: [DatePipe, DecimalPipe, FormsModule, ProbBar],
  template: `
    <h1>Operar</h1>
    @if (state(); as s) {
      @if (s.mode === 'LIVE') {
        <div class="notice"><b>Conta REAL.</b> Cada ordem usa dinheiro de verdade. O sistema confere as travas, mas
          gap e slippage podem fazer a perda passar do stop.</div>
      } @else if (s.mode === 'SHADOW') {
        <div class="notice">Modo <b>SHADOW</b>: as ordens são registradas e conferidas, mas não vão para o MT5.
          Para enviar: trading.mode = DEMO (conta demo) ou LIVE (conta real) e InpExecute = true no EA.</div>
      }

      <div class="grid two">
        <section class="panel">
          <h3>Conta</h3>
          @if (s.account; as a) {
            <table>
              <tr><td>Conta</td><td class="mono">{{ a.account }} · {{ a.server }} <span class="badge"
                [class.warn]="a.tradeMode === 'REAL'">{{ a.tradeMode }}</span></td></tr>
              <tr><td>Saldo / patrimônio</td><td class="mono">US$ {{ a.balance | number: '1.2-2' }} /
                US$ {{ a.equity | number: '1.2-2' }}</td></tr>
              <tr><td>Margem</td><td class="mono">{{ marginText() }}</td></tr>
              <tr><td>EA</td><td><span class="badge" [class.ok]="eaAge() <= 30" [class.high]="eaAge() > 30">
                {{ eaAge() <= 30 ? 'conectado' : 'sem heartbeat' }}</span>
                <span class="muted small"> há {{ eaAge() }} s · {{ eaMode() }}</span></td></tr>
              <tr><td>Sistema</td><td><span class="badge" [class.warn]="s.mode === 'LIVE'">{{ s.mode }}</span></td></tr>
            </table>
          } @else {
            <p class="muted">O EA ainda não mandou heartbeat. Ele está anexado a um gráfico no MT5?</p>
          }
          <div class="actions">
            @if (s.blocked) {
              <button (click)="block(false)">Desligar BLOCK</button>
            } @else {
              <button (click)="block(true)">BLOCK (sem entradas novas)</button>
            }
            <button class="danger" (click)="flatten()" [disabled]="s.positions.length === 0 && !s.flattening">
              {{ s.flattening ? 'FLATTEN em andamento…' : 'FLATTEN (fechar tudo)' }}</button>
          </div>
          <p class="muted small">Limites (trading.risk): teto por trade {{ s.limits.global.maxRiskPerTradePct }}% ou
            US$ {{ s.limits.global.maxRiskPerTradeUsd | number: '1.2-2' }} · perda máx. do dia
            {{ s.limits.global.maxDailyLossPct }}% · até {{ s.limits.global.maxPositions }} posições · stop
            {{ s.limits.exits.stopAtr }} ATR, alvo {{ s.limits.exits.targetR }} × stop · lote até
            {{ s.limits.orders.maxLot }}.</p>
        </section>

        <section class="panel">
          <h3>Nova ordem</h3>
          <div class="ticket">
            <label>Par
              <select [(ngModel)]="symbol" (ngModelChange)="resetPreview()">
                @for (sym of s.symbols; track sym) { <option [value]="sym">{{ sym }}</option> }
              </select>
            </label>
            <label>Lote
              <input type="number" [(ngModel)]="lot" (ngModelChange)="resetPreview()" min="0.01"
                     [max]="s.limits.orders.maxLot" step="0.01" />
            </label>
            <button (click)="saveLot()" title="Usar este lote como padrão">Salvar lote</button>
          </div>
          <div class="models">
            @for (m of models; track m) {
              <div>
                <div class="small muted">{{ names[m] }} · 60 min</div>
                @if (pred(m, 60); as p) {
                  <prob-bar [down]="p.pDown" [flat]="p.pFlat" [up]="p.pUp" />
                } @else { <span class="muted small">sem previsão</span> }
              </div>
            }
          </div>
          <div class="actions">
            <button (click)="calc('BUY')">Calcular COMPRA</button>
            <button (click)="calc('SELL')">Calcular VENDA</button>
          </div>

          @if (preview(); as p) {
            <div class="preview">
              <table>
                <tr><td>Entrada (cotação agora)</td><td class="mono">{{ p.refPrice ?? '—' }}</td></tr>
                <tr><td>Stop / alvo (aprox.)</td><td class="mono">
                  <span class="down">{{ p.slPrice !== null ? (p.slPrice | number: priceFormat()) : '—' }}</span> /
                  <span class="up">{{ p.tpPrice !== null ? (p.tpPrice | number: priceFormat()) : '—' }}</span></td></tr>
                <tr><td><b>Perda máxima se o stop bater</b></td><td class="mono"><b class="down">
                  {{ p.riskUsd !== null ? 'US$ ' + (p.riskUsd | number: '1.2-2') : '—' }}</b>
                  {{ p.riskPct !== null ? '(' + (p.riskPct | number: '1.1-1') + '% do saldo)' : '' }}</td></tr>
                <tr><td>Ganho se o alvo bater</td><td class="mono up">
                  {{ p.rewardUsd !== null ? 'US$ ' + (p.rewardUsd | number: '1.2-2') : '—' }}</td></tr>
                <tr><td>Saída por tempo</td><td>{{ p.closeAfterMinutes ? p.closeAfterMinutes + ' min' : 'só SL/TP' }}</td></tr>
              </table>
              <table class="checks">
                @for (c of p.checks; track c.gate) {
                  <tr [class.bad]="!c.ok">
                    <td>{{ c.ok ? '✓' : '✗' }}</td><td>{{ c.gate }}</td><td class="small">{{ c.value }}</td>
                    <td class="small muted">{{ c.limit }}</td>
                  </tr>
                }
              </table>
              @if (p.ok) {
                <button class="primary big" (click)="send(p)" [disabled]="sending()">
                  {{ sending() ? 'Enviando…' : (p.side === 'BUY' ? 'COMPRAR ' : 'VENDER ') + p.lot + ' ' + p.symbol }}</button>
              } @else {
                <p class="down">Ordem bloqueada pelas travas acima.</p>
              }
            </div>
          }
          @if (result(); as r) {
            <p class="small" [class.down]="!r.orderId">{{ resultText(r) }}</p>
          }
        </section>
      </div>

      <section class="panel" style="margin-top:16px">
        <h3>Posições abertas (deste sistema)</h3>
        <div class="table-wrap"><table>
          <tr><th>Ticket</th><th>Par</th><th>Lado</th><th class="right">Lote</th><th class="right">Abertura</th>
            <th class="right">SL</th><th class="right">TP</th><th class="right">Resultado</th><th>Desde (UTC)</th><th></th></tr>
          @for (p of s.positions; track p.ticket) {
            <tr>
              <td class="mono">{{ p.ticket }}</td>
              <td class="mono">{{ p.symbol }}</td>
              <td><span class="badge" [class.buy]="p.side === 'BUY'" [class.sell]="p.side === 'SELL'">{{ p.side === 'BUY' ? 'COMPRA' : 'VENDA' }}</span></td>
              <td class="right mono">{{ p.volume }}</td>
              <td class="right mono">{{ p.openPrice }}</td>
              <td class="right mono">{{ p.sl }}</td>
              <td class="right mono">{{ p.tp }}</td>
              <td class="right mono" [class.up]="(p.profit ?? 0) > 0" [class.down]="(p.profit ?? 0) < 0">
                US$ {{ p.profit | number: '1.2-2' }}</td>
              <td class="small mono">{{ p.openTime | date: 'dd/MM HH:mm' : 'UTC' }}</td>
              <td><button (click)="close(p.ticket)">Fechar</button></td>
            </tr>
          } @empty {
            <tr><td colspan="10" class="muted">Nenhuma posição aberta.</td></tr>
          }
        </table></div>
      </section>

      <section class="panel" style="margin-top:16px">
        <h3>Ordens recentes</h3>
        <div class="table-wrap"><table>
          <tr><th>#</th><th>Quando (UTC)</th><th>Ação</th><th>Par</th><th class="right">Lote</th>
            <th class="right">Risco</th><th>Status</th><th>Detalhe</th></tr>
          @for (o of s.orders; track o.id) {
            <tr>
              <td class="mono">{{ o.id }}</td>
              <td class="small mono">{{ o.createdAt | date: 'dd/MM HH:mm:ss' : 'UTC' }}</td>
              <td>{{ o.action === 'OPEN' ? (o.side === 'BUY' ? 'compra' : 'venda') : 'fechar #' + o.ticket }}</td>
              <td class="mono">{{ o.symbol }}</td>
              <td class="right mono">{{ o.volume }}</td>
              <td class="right mono">{{ o.riskUsd !== null ? 'US$ ' + (o.riskUsd | number: '1.2-2') : '' }}</td>
              <td><span class="badge" [class.ok]="o.status === 'FILLED'"
                [class.high]="o.status === 'REJECTED' || o.status === 'EXPIRED'">{{ o.status }}</span></td>
              <td class="small muted">{{ o.message }}</td>
            </tr>
          } @empty {
            <tr><td colspan="8" class="muted">Nenhuma ordem ainda.</td></tr>
          }
        </table></div>
      </section>
    } @else {
      <p class="muted">Carregando…</p>
    }
  `,
  styles: `
    .actions { display: flex; gap: 8px; flex-wrap: wrap; margin: 12px 0; }
    .ticket { display: flex; gap: 12px; align-items: end; flex-wrap: wrap; }
    .ticket label { display: flex; flex-direction: column; gap: 4px; font-size: 12px; color: var(--muted); }
    select, input { font: inherit; padding: 6px 8px; border-radius: 8px; border: 1px solid var(--border);
      background: var(--panel-2); color: var(--text); min-width: 110px; }
    .models { display: grid; grid-template-columns: repeat(auto-fit, minmax(140px, 1fr)); gap: 12px; margin-top: 12px; }
    .preview { margin-top: 8px; }
    .checks td { padding: 3px 6px; }
    .checks tr.bad td { color: var(--down); }
    button.big { width: 100%; padding: 10px; font-weight: 700; margin-top: 12px; }
    button.danger { border-color: var(--down); color: var(--down); }
  `,
})
export class Operar implements OnInit, OnDestroy {
  private api = inject(Api);
  protected names = MODEL_NAMES;
  protected models = MODEL_ORDER;
  protected state = signal<TradeState | null>(null);
  protected latest = signal<LatestPredictions | null>(null);
  protected preview = signal<OrderPreview | null>(null);
  protected result = signal<Submit | null>(null);
  protected sending = signal(false);
  protected symbol = 'EURUSD';
  protected lot = 0.01;
  private lotLoaded = false;
  private detail = computed(() => {
    try { return JSON.parse(this.state()?.account?.detailJson ?? '{}'); } catch { return {}; }
  });
  private timer?: ReturnType<typeof setInterval>;

  ngOnInit(): void {
    this.load();
    this.timer = setInterval(() => this.load(), 5_000);
  }

  ngOnDestroy(): void {
    clearInterval(this.timer);
  }

  protected eaAge(): number {
    const t = this.state()?.account?.reportedAt;
    return t ? Math.round((Date.now() - new Date(t).getTime()) / 1000) : 9999;
  }

  protected eaMode(): string {
    return this.detail()['ea_mode'] === 'EXECUTE' ? 'envio de ordens LIGADO no EA' : 'EA só registra (InpExecute=false)';
  }

  protected marginText(): string {
    const d = this.detail();
    if (!d['margin']) return 'sem margem em uso';
    return `US$ ${Number(d['margin']).toFixed(2)} usada · nível ${Number(d['margin_level']).toFixed(0)}%`;
  }

  protected priceFormat(): string {
    return this.symbol.endsWith('JPY') ? '1.3-3' : '1.5-5';
  }

  protected pred(model: string, h: number): Prediction | undefined {
    return this.latest()?.predictions.find((p) => p.symbol === this.symbol && p.model === model && p.horizonMin === h);
  }

  protected resetPreview(): void {
    this.preview.set(null);
    this.result.set(null);
  }

  protected calc(side: 'BUY' | 'SELL'): void {
    this.result.set(null);
    this.api.preview(this.symbol, side, this.lot).subscribe((p) => this.preview.set(p));
  }

  protected send(p: OrderPreview): void {
    const real = this.state()?.mode === 'LIVE';
    const text = `${p.side === 'BUY' ? 'COMPRAR' : 'VENDER'} ${p.lot} ${p.symbol}\n` +
      `Perda máxima se o stop bater: US$ ${p.riskUsd?.toFixed(2)}` + (real ? '\n\nCONTA REAL — confirmar?' : '\n\nConfirmar?');
    if (!confirm(text)) return;
    this.sending.set(true);
    this.api.submit(p.symbol, p.side, p.lot).subscribe({
      next: (r) => { this.result.set(r); this.preview.set(null); this.sending.set(false); this.load(); },
      error: (e) => { this.result.set(e?.error ?? null); this.preview.set(e?.error?.preview ?? null); this.sending.set(false); },
    });
  }

  protected resultText(r: Submit): string {
    if (!r.orderId) return 'Ordem recusada: ' + (r.preview?.checks ?? []).filter((c) => !c.ok).map((c) => c.gate).join(', ');
    if (r.status === 'SHADOW') return `Ordem #${r.orderId} registrada em SHADOW (não vai para o MT5).`;
    return `Ordem #${r.orderId} enviada ao EA (válida por ${this.state()?.limits.orders.validitySeconds ?? 30} s). Acompanhe em "Ordens recentes".`;
  }

  protected saveLot(): void {
    this.api.setLot(this.lot).subscribe({ next: (r) => (this.lot = r.lot), error: (e) => alert(e?.error?.message ?? 'Lote recusado') });
  }

  protected close(ticket: number): void {
    if (!confirm(`Fechar a posição ${ticket} agora?`)) return;
    this.api.closePosition(ticket).subscribe(() => this.load());
  }

  protected flatten(): void {
    if (!confirm('FLATTEN: fechar TODAS as posições deste sistema e ligar o BLOCK?')) return;
    this.api.flatten().subscribe(() => this.load());
  }

  protected block(on: boolean): void {
    this.api.block(on).subscribe(() => this.load());
  }

  private load(): void {
    this.api.tradeState().subscribe({
      next: (s) => {
        this.state.set(s);
        if (!this.lotLoaded) { this.lot = s.lot; this.lotLoaded = true; }
      },
      error: () => this.state.set(null),
    });
    this.api.latest().subscribe({ next: (l) => this.latest.set(l), error: () => {} });
  }
}
