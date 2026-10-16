import { Component, OnDestroy, OnInit, computed, effect, inject, signal } from '@angular/core';
import { DatePipe, DecimalPipe } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { Api, LatestPredictions, MODEL_NAMES, MODEL_ORDER, OrderPreview, PositionDetail, Prediction, Submit, TradeState } from '../api';
import { ProbBar } from './prob-bar';
import { Tip } from './tip';
import { Brt } from '../brt';
import { HELP } from '../help';
import { MARKETS, MarketState } from '../market';
import { MyTrades } from './my-trades';

@Component({
  selector: 'page-operar',
  imports: [DatePipe, DecimalPipe, FormsModule, ProbBar, Tip, MyTrades, Brt, RouterLink],
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
              <tr><td>Saldo / patrimônio <tip key="patrimonio" /></td><td class="mono">US$ {{ a.balance | number: '1.2-2' }} /
                US$ {{ a.equity | number: '1.2-2' }}</td></tr>
              <tr><td>Margem <tip key="margem" /></td><td class="mono">{{ marginText() }}</td></tr>
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
          @if (s.loss_limits; as ll) {
            <p class="small">Trava de perda: <b>{{ ll.maxDailyLossPct }}%</b> no dia e <b>{{ ll.maxWeeklyLossPct }}%</b> na semana
              <tip key="lossProfile" /> <span class="muted">— do perfil de risco da página <a routerLink="/risco">Risco</a></span></p>
          }
          <p class="muted small">Limites (trading.risk): teto por trade {{ s.limits.global.maxRiskPerTradePct }}% ou
            US$ {{ s.limits.global.maxRiskPerTradeUsd | number: '1.2-2' }} · até {{ s.limits.global.maxPositions }} posições · stop
            {{ s.limits.exits.stopAtr }} ATR, alvo {{ s.limits.exits.targetR }} × stop · lote até
            {{ s.limits.orders.maxLot }}.</p>
        </section>

        <section class="panel">
          <h3>Nova ordem</h3>
          <div class="ticket">
            <label>Par
              <select [(ngModel)]="symbol" (ngModelChange)="resetPreview()">
                @for (sym of marketSymbols(s); track sym) { <option [value]="sym">{{ sym }}</option> }
              </select>
            </label>
            <label>Lote <tip key="lote" />
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
                <tr><td>Stop / alvo (aprox.) <tip text="Calculados sobre a cotação de agora; o EA recalcula sobre o preço real da execução, com as mesmas distâncias." /></td><td class="mono">
                  <span class="down">{{ p.slPrice !== null ? (p.slPrice | number: priceFormat()) : '—' }}</span> /
                  <span class="up">{{ p.tpPrice !== null ? (p.tpPrice | number: priceFormat()) : '—' }}</span></td></tr>
                <tr><td><b>Perda máxima se o stop bater</b> <tip key="R" /></td><td class="mono"><b class="down">
                  {{ p.riskUsd !== null ? 'US$ ' + (p.riskUsd | number: '1.2-2') : '—' }}</b>
                  {{ p.riskPct !== null ? '(' + (p.riskPct | number: '1.1-1') + '% do saldo)' : '' }}</td></tr>
                <tr><td>Ganho se o alvo bater</td><td class="mono up">
                  {{ p.rewardUsd !== null ? 'US$ ' + (p.rewardUsd | number: '1.2-2') : '—' }}</td></tr>
                <tr><td>Saída por tempo <tip key="closeAfterMinutes" /></td><td>{{ p.closeAfterMinutes ? p.closeAfterMinutes + ' min' : 'só SL/TP' }}</td></tr>
              </table>
              <table class="checks">
                @for (c of p.checks; track c.gate) {
                  <tr [class.bad]="!c.ok">
                    <td>{{ c.ok ? '✓' : '✗' }}</td><td>{{ c.gate }} @if (checkHelp[c.gate]; as h) { <tip [text]="h" /> }</td><td class="small">{{ c.value }}</td>
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
        @if (s.money; as m) {
          <div class="protect">
            <div class="lockbar">
              <button [class.primary]="m.timeLockEnabled" (click)="toggleTimeLock(!m.timeLockEnabled)" [disabled]="lockSaving()">
                Prorrogar com lucro na saída por tempo: <b>{{ m.timeLockEnabled ? 'LIGADO' : 'desligado' }}</b></button>
              <tip text="Vale para TODAS as posições que chegam ao fim do tempo (roleta e manuais). Com lucro, em vez de fechar a mercado, o stop vai para um preço que garante parte do lucro e o alvo para um pouco além do preço. Depois, a cada nova máxima de lucro, o stop sobe, o alvo vai à frente e a janela renova; sem nova máxima na janela, ou no teto, fecha a mercado. Sem lucro suficiente, fecha como sempre." />
              <span class="small muted">No fim do tempo, com lucro ≥ US$ {{ m.timeLockMinProfitUsd | number: '1.2-2' }}: o stop
                garante {{ m.timeLockKeepPct | number: '1.0-0' }}% do maior lucro e o alvo fica +{{ m.timeLockExtraPct | number: '1.0-0' }}% à frente,
                andando a cada nova máxima (+{{ m.timeLockStepPct | number: '1.0-0' }}%); fecha sem nova máxima em {{ m.timeLockMinutes }} min ou no teto
                de {{ m.timeLockMaxMinutes }} min. Valores na página <a routerLink="/risco">Risco</a> (parte 2).</span>
            </div>
            @if (m.timeLockEnabled && !eaAtLeast(2, 4)) {
              <p class="small down">O EA conectado ({{ eaVersion() || 'versão desconhecida' }}) não sabe mover o alvo: as posições
                fecham por tempo como antes. Rode mql5\\install.ps1 e recarregue o JevExecutor (2.4).</p>
            }
            <p class="small muted">
              Trailing: @if (m.trailEnabled) { <b class="up">ligado</b> — zero a zero com US$ {{ m.trailStartUsd | number: '1.2-2' }} de lucro,
                depois segue o preço a US$ {{ m.trailDistanceUsd | number: '1.2-2' }}, de US$ {{ m.trailStepUsd | number: '1.2-2' }} em
                US$ {{ m.trailStepUsd | number: '1.2-2' }} } @else { <b>desligado</b> }
              · Proteção de lucro: @if (m.protectEnabled) { <b class="up">ligada</b> — lucro ≥ US$ {{ m.protectTriggerUsd | number: '1.2-2' }}
                → garante US$ {{ m.protectLockUsd | number: '1.2-2' }} ({{ marketNames(m.protectMarkets) }}) } @else { <b>desligada</b> }
              · Stop/alvo em US$ nas ordens novas: @if (m.enabled) { <b class="up">ligado</b> (US$ {{ m.stopUsd | number: '1.2-2' }} /
                US$ {{ m.takeUsd | number: '1.2-2' }}) } @else { <b>desligado</b> (vale o ATR) }
            </p>
          </div>
        }
        <div class="table-wrap"><table class="positions">
          <tr><th>Ticket</th><th>Par</th><th>Lado</th><th class="right">Lote</th><th class="right">Abertura</th>
            <th class="right">SL <tip text="Stop no servidor da corretora: fecha com perda se o preço chegar lá, mesmo com o computador desligado. Embaixo, quanto dá em US$ (positivo = lucro já garantido)." /></th><th class="right">TP <tip text="Alvo no servidor da corretora: fecha com lucro se o preço chegar lá. Embaixo, quanto dá em US$." /></th><th class="right">Resultado <tip key="patrimonio" /></th>
            <th>Origem <tip text="Roleta (tendência = lado mais provável; pullback = LATERAL contra a banda de Bollinger) ou ordem manual. Modelo, horizonte e a probabilidade do lado na hora da entrada." /></th>
            <th>Fecha em <tip text="Quanto falta para a saída por tempo (ou para o fim da prorrogação com lucro). Antes disso, só o stop e o alvo fecham a posição." /></th><th>Desde (UTC)</th><th></th></tr>
          @for (p of s.positions; track p.ticket) {
            @let d = s.details?.[p.ticket];
            <tr class="main">
              <td class="mono">{{ p.ticket }}</td>
              <td class="mono">{{ p.symbol }}</td>
              <td><span class="badge" [class.buy]="p.side === 'BUY'" [class.sell]="p.side === 'SELL'">{{ p.side === 'BUY' ? 'COMPRA' : 'VENDA' }}</span></td>
              <td class="right mono">{{ p.volume }}</td>
              <td class="right mono">{{ p.openPrice }}</td>
              <td class="right mono">{{ p.sl }}
                @if (d?.slUsd != null) { <div class="small" [class.up]="d!.slUsd! >= 0" [class.down]="d!.slUsd! < 0">{{ usd(d!.slUsd!) }}</div> }</td>
              <td class="right mono">{{ p.tp }}
                @if (d?.tpUsd != null) { <div class="small up">{{ usd(d!.tpUsd!) }}</div> }</td>
              <td class="right mono" [class.up]="(p.profit ?? 0) > 0" [class.down]="(p.profit ?? 0) < 0">
                US$ {{ p.profit | number: '1.2-2' }}</td>
              <td class="small">{{ originText(d) }}</td>
              <td class="mono" [class.warn]="d?.timeLockUntil">{{ remaining(d) }}</td>
              <td class="small mono" [brt]="p.openTime">{{ p.openTime | date: 'dd/MM HH:mm' : 'UTC' }}</td>
              <td><button (click)="close(p.ticket)">Fechar</button></td>
            </tr>
            <tr class="sub"><td></td><td colspan="11" class="small muted">{{ detailText(d) }}</td></tr>
          } @empty {
            <tr><td colspan="12" class="muted">Nenhuma posição aberta.</td></tr>
          }
        </table></div>
      </section>

      <section class="panel" style="margin-top:16px">
        <my-trades [days]="30" />
      </section>

      <section class="panel" style="margin-top:16px">
        <h3>Ordens recentes</h3>
        <div class="table-wrap"><table>
          <tr><th>#</th><th>Quando (UTC)</th><th>Ação</th><th>Par</th><th class="right">Lote</th>
            <th class="right">Risco <tip key="R" /></th><th>Status <tip text="PENDING: esperando o EA · SENT: entregue · FILLED: executada · REJECTED: recusada (motivo ao lado) · EXPIRED: o EA não buscou a tempo · SHADOW: só registrada." /></th><th>Detalhe</th></tr>
          @for (o of s.orders; track o.id) {
            <tr>
              <td class="mono">{{ o.id }}</td>
              <td class="small mono" [brt]="o.createdAt">{{ o.createdAt | date: 'dd/MM HH:mm:ss' : 'UTC' }}</td>
              <td>{{ o.action === 'OPEN' ? (o.side === 'BUY' ? 'compra' : 'venda') : (o.action === 'MODIFY' ? 'mover stop #' : 'fechar #') + o.ticket }}</td>
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
    .profile { display: flex; gap: 8px; flex-wrap: wrap; align-items: center; margin: 4px 0 8px; }
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
    .protect { margin-bottom: 8px; }
    .lockbar { display: flex; gap: 8px; align-items: center; flex-wrap: wrap; }
    .positions tr.main td { border-bottom: none; }
    .positions tr.sub td { padding-top: 0; }
    td.warn { color: var(--warn, #d97706); }
  `,
})
export class Operar implements OnInit, OnDestroy {
  private api = inject(Api);
  private marketState = inject(MarketState);
  protected names = MODEL_NAMES;
  protected models = MODEL_ORDER;
  protected state = signal<TradeState | null>(null);
  protected latest = signal<LatestPredictions | null>(null);
  protected preview = signal<OrderPreview | null>(null);
  protected result = signal<Submit | null>(null);
  protected sending = signal(false);
  protected lockSaving = signal(false);
  /** Relógio de 1 s para a contagem até a saída por tempo. */
  protected now = signal(Date.now());
  private clock?: ReturnType<typeof setInterval>;
  /** O que cada trava confere (nomes como vêm do OrderService). */
  protected checkHelp: Partial<Record<string, string>> = {
    'EA conectado': 'O EA precisa ter mandado sinal de vida nos últimos 30 s: sem ele, ninguém executaria a ordem.',
    'Modo × conta': 'SHADOW só registra. DEMO só envia para conta demo; LIVE só para conta real — nunca o contrário.',
    'BLOCK / FLATTEN': 'Com BLOCK ligado (ou FLATTEN em andamento) não entra posição nova; fechar continua liberado.',
    'Cotação': 'A cotação vem do EA a cada 5 s; velha demais = não dá para calcular o risco com segurança.',
    'ATR (última previsão)': HELP['atrMaxAgeMinutes'] ?? '',
    'Lote': 'Entre o lote mínimo da corretora e o lote máximo da página Risco, no passo da corretora (0,01).',
    'Risco da ordem': HELP['maxRiskPerTradePct'] ?? '',
    'Posições abertas': HELP['maxPositions'] ?? '',
    'Par livre': 'Só uma posição por símbolo: evita dobrar a aposta no mesmo ativo sem querer.',
    'Risco aberto total': HELP['maxOpenRiskPct'] ?? '',
    'Exposição líquida a USD': HELP['maxUsdNetExposurePct'] ?? '',
    'Perda do dia': HELP['maxDailyLossPct'] ?? '',
    'Perda da semana': HELP['maxWeeklyLossPct'] ?? '',
    'Nível de margem': HELP['minMarginLevelPct'] ?? '',
    'Sessão até a saída por tempo': HELP['sessionMarginMinutes'] ?? '',
    'Spread (gate 3)': HELP['maxSpreadRatio'] ?? '',
    'Fora de blackout (gate 2)': HELP['blackoutBeforeMinutes'] ?? '',
    'Símbolo': 'O símbolo precisa ter a especificação da corretora (vem do MT5) para o risco ser calculado.',
  };
  protected symbol = 'EURUSD';
  protected lot = 0.01;
  private lotLoaded = false;
  private detail = computed(() => {
    try { return JSON.parse(this.state()?.account?.detailJson ?? '{}'); } catch { return {}; }
  });
  private timer?: ReturnType<typeof setInterval>;

  constructor() {
    effect(() => {
      const m = this.marketState.market();
      const list = this.state()?.symbols_by_market?.[m] ?? [];
      if (list.length && !list.includes(this.symbol)) { this.symbol = list[0]; this.resetPreview(); }
      this.api.latest(m).subscribe({ next: (l) => this.latest.set(l), error: () => {} });
    });
  }

  protected marketSymbols(s: TradeState): string[] {
    return s.symbols_by_market?.[this.marketState.market()] ?? [];
  }

  ngOnInit(): void {
    this.load();
    this.timer = setInterval(() => this.load(), 5_000);
    this.clock = setInterval(() => this.now.set(Date.now()), 1_000);
  }

  ngOnDestroy(): void {
    clearInterval(this.timer);
    clearInterval(this.clock);
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

  protected eaVersion(): string {
    return String(this.detail()['ea_version'] ?? '');
  }

  /** Versão do EA conectado ≥ major.minor (o heartbeat traz "JevExecutor/2.4"). */
  protected eaAtLeast(major: number, minor: number): boolean {
    const m = /\/(\d+)\.(\d+)/.exec(this.eaVersion());
    if (!m) return false;
    const a = +m[1], b = +m[2];
    return a > major || (a === major && b >= minor);
  }

  protected toggleTimeLock(on: boolean): void {
    const m = this.state()?.money;
    const text = on
      ? `Ligar a prorrogação com lucro?\n\nNo fim do tempo, posição com lucro ≥ US$ ${m?.timeLockMinProfitUsd} não fecha a mercado: ` +
        `o stop garante ${m?.timeLockKeepPct}% do maior lucro e o alvo fica +${m?.timeLockExtraPct}% à frente, andando a cada nova máxima; ` +
        `fecha sem nova máxima em ${m?.timeLockMinutes} min ou no teto de ${m?.timeLockMaxMinutes} min. ` +
        `Vale para todas as posições (roleta e manuais).`
      : 'Desligar a prorrogação com lucro? As posições voltam a fechar a mercado no fim do tempo (as já prorrogadas seguem até o fim da prorrogação).';
    if (!confirm(text)) return;
    this.lockSaving.set(true);
    this.api.setTimeLock(on).subscribe({
      next: () => { this.lockSaving.set(false); this.load(); },
      error: (e) => { this.lockSaving.set(false); alert(e?.error?.message ?? 'não consegui mudar'); },
    });
  }

  protected marketNames(codes: string[] | undefined): string {
    return (codes ?? []).map((c) => MARKETS.find((m) => m.code === c)?.label ?? c).join(", ");
  }

  protected usd(v: number): string {
    return (v >= 0 ? '+' : '−') + 'US$ ' + Math.abs(v).toFixed(2);
  }

  protected originText(d: PositionDetail | undefined): string {
    if (!d) return '—';
    if (d.origin === 'externa') return 'fora do sistema';
    const who = d.origin === 'roleta' ? 'Roleta · ' + (d.strategy === 'pullback' ? 'pullback' : 'tendência') : 'Manual';
    const parts = [who];
    if (d.model) parts.push(`${d.model}${d.horizonMin ? ' ' + d.horizonMin + ' min' : ''}`);
    if (d.pSide != null) parts.push(`P ${(d.pSide * 100).toFixed(0)}%`);
    if (d.strategy === 'pullback' && d.pctB != null) parts.push(`%B ${d.pctB.toFixed(2)}`);
    return parts.join(' · ');
  }

  /** Contagem até a saída por tempo (ou o fim da prorrogação). */
  protected remaining(d: PositionDetail | undefined): string {
    const until = d?.timeLockUntil ?? d?.closeAfterUtc;
    if (!until) return d?.origin === 'externa' ? '—' : 'só SL/TP';
    const ms = new Date(until).getTime() - this.now();
    const tag = d?.timeLockUntil ? ' (prorrogada)' : '';
    if (ms <= 0) return 'fechando…' + tag;
    const s = Math.floor(ms / 1000), h = Math.floor(s / 3600), mm = Math.floor((s % 3600) / 60), ss = s % 60;
    return (h ? h + ':' + String(mm).padStart(2, '0') : String(mm)) + ':' + String(ss).padStart(2, '0') + tag;
  }

  protected detailText(d: PositionDetail | undefined): string {
    if (!d) return '';
    if (d.origin === 'externa') return 'Posição sem ordem deste sistema (o trailing e a prorrogação valem só para as do sistema).';
    const parts: string[] = [];
    if (d.stopMode === 'USD') parts.push('Stop/alvo de entrada: em US$ (página Risco, parte 2)');
    else if (d.stopAtr != null) parts.push(`Stop de entrada: ${d.stopAtr.toFixed(2)} ATR, alvo ${d.targetR?.toFixed(2) ?? '—'} × o stop`);
    if (d.riskUsdPlanned != null) parts.push(`perda planejada US$ ${d.riskUsdPlanned.toFixed(2)}`);
    parts.push(d.trailMoves > 0 ? `trailing moveu o stop ${d.trailMoves}×${d.trailLast ? ' (último: ' + d.trailLast + ')' : ''}`
      : 'trailing ainda não moveu o stop');
    if (d.timeLockUntil) {
      const st = d.timeLockStatus === 'FILLED' ? 'stop e alvo movidos' : d.timeLockStatus === 'REJECTED' || d.timeLockStatus === 'EXPIRED'
        ? `o EA recusou (${d.timeLockMessage ?? d.timeLockStatus}): fecha a mercado` : `pedido ${d.timeLockStatus ?? ''}`;
      const peak = d.timeLockPeakUsd != null ? `, maior lucro US$ ${d.timeLockPeakUsd.toFixed(2)}` : '';
      const moves = d.timeLockMoves ? `, andou ${d.timeLockMoves}× por nova máxima` : '';
      parts.push(`prorrogada com lucro: ${st}${peak}${moves}`);
    }
    return parts.join(' · ');
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
    this.api.latest(this.marketState.market()).subscribe({ next: (l) => this.latest.set(l), error: () => {} });
  }
}
