import { Component, OnDestroy, OnInit, inject, signal } from '@angular/core';
import { DatePipe, DecimalPipe } from '@angular/common';
import { HttpClient } from '@angular/common/http';
import { MODEL_NAMES } from '../api';
import { MARKETS } from '../market';
import { Tip } from './tip';
import { Brt } from '../brt';

interface HfConfig { on: boolean; model: string; horizon: number; lot: number | null; takeUsd: number; stopAtr: number;
  startedAt: string | null; stoppedAt: string | null; stopReason: string | null }
interface HfForm { model: string; horizon: number; lot: number | null; takeUsd: number; stopAtr: number }
interface Viab { market: string; symbol: string; lot: number; stopDistance: number | null; stopPoints: number | null; spreadPoints: number | null; ok: boolean }

@Component({
  selector: 'page-alta-frequencia',
  imports: [DatePipe, DecimalPipe, Tip, Brt],
  template: `
    <div class="head">
      <h1>Alta frequência</h1>
      <button class="danger big" (click)="stopAll()">PARAR TODA A ALTA FREQUÊNCIA</button>
    </div>
    <div class="notice danger">
      <b>Ordens automáticas com alvo pequeno em dólar, sem as travas de risco por ordem.</b> Em cada mercado ligado, cada
      previsão nova vira uma ordem em cada símbolo no lado mais provável (pula LATERAL). Ela fecha no <b>alvo em US$</b>
      (ex.: +US$ 0,10 com o lote da ordem) ou, se não chegar lá, <b>por tempo</b> no horizonte. O <b>stop de proteção</b>
      (3 ATR) fica no servidor só para emergência: queda do MT5, do PC ou movimento extremo.
      Valem só as travas técnicas (EA, cotação, lote, sessão, BLOCK, alvo além de 2 spreads) e a <b>perda do dia/semana</b>,
      que para tudo.
      <br><b>Alvo pequeno não cria vantagem</b>: muitas operações fecham com o lucro pequeno, e as que vão contra
      saem por tempo com perdas maiores. Na média, o resultado é a vantagem de direção da previsão menos o spread,
      e isso deu negativo para operar toda previsão em quase todos os mercados (Laboratório).
      @if (state(); as s) {
        <div class="small">Modo <b>{{ s.mode }}</b> · perda do dia {{ s.loss_limits?.maxDailyLossPct }}%, semana {{ s.loss_limits?.maxWeeklyLossPct }}%
          @if (s.blocked) { · <b class="down">BLOCK ligado: nenhuma ordem sai</b> }</div>
      }
    </div>

    @if (state(); as s) {
      <div class="cards">
        @for (m of markets; track m.code) {
          @let c = s.markets[m.code];
          @let f = form(m.code);
          <section class="panel card" [class.on]="c?.on">
            <h3>{{ m.label }} @if (c?.on) { <span class="badge ok">LIGADA</span> } @else { <span class="badge">desligada</span> }</h3>
            @if (c?.on) {
              <p class="small">{{ names[c!.model] ?? c!.model }} · <b>{{ c!.horizon }} min</b> · lote {{ c!.lot ?? 'mínimo' }} ·
                alvo <b>US$ {{ c!.takeUsd | number: '1.2-2' }}</b> · stop de proteção {{ c!.stopAtr }} ATR ·
                desde <span [brt]="c!.startedAt">{{ c!.startedAt | date: 'dd/MM HH:mm' : 'UTC' }} UTC</span></p>
              <button class="danger" (click)="stop(m.code)">Parar</button>
            } @else {
              <div class="form">
                <label class="small">Modelo <select [value]="f.model" (change)="patch(m.code, { model: $any($event.target).value })">
                  @for (x of ['B', 'A', 'E']; track x) { <option [value]="x">{{ x }} · {{ names[x] }}</option> }</select></label>
                <label class="small">Horizonte <select [value]="f.horizon" (change)="patch(m.code, { horizon: +$any($event.target).value })">
                  <option [value]="15">15 min</option><option [value]="60">60 min</option></select></label>
                <label class="small">Lote <input type="number" min="0.01" step="0.01" [value]="f.lot ?? ''" placeholder="mínimo"
                  (input)="patch(m.code, { lot: $any($event.target).value ? +$any($event.target).value : null })" /></label>
                <label class="small">Alvo US$ <input type="number" min="0.01" step="0.05" [value]="f.takeUsd" (input)="patch(m.code, { takeUsd: +$any($event.target).value })" /></label>
                <label class="small">Stop de proteção (ATR) <input type="number" min="1" step="0.5" [value]="f.stopAtr" (input)="patch(m.code, { stopAtr: +$any($event.target).value })" /></label>
              </div>
              <div class="actions">
                <button (click)="viability(m.code)">Onde o alvo cabe</button>
                <button class="primary" (click)="start(m.code)">Ligar</button>
              </div>
              @if (c?.stopReason) { <p class="small muted">Parada: {{ c!.stopReason }}</p> }
              @if (viab()[m.code]; as v) {
                <table class="small">
                  <tr><th>Símbolo</th><th class="right">Alvo a</th><th class="right">Spread</th><th></th></tr>
                  @for (r of v; track r.symbol) {
                    <tr><td class="mono">{{ r.symbol }}</td><td class="right mono">{{ r.stopDistance === null ? '—' : (r.stopDistance | number: '1.0-5') }}</td>
                      <td class="right mono">{{ r.spreadPoints ?? '—' }} pts</td>
                      <td>@if (r.ok) { <span class="badge ok">cabe</span> } @else { <span class="badge high" title="O alvo ficaria a menos de 2 spreads (ou do mínimo da corretora): a ordem é recusada">perto demais</span> }</td></tr>
                  }
                </table>
              }
            }
            @let n = count(m.code);
            @let r = closed(m.code);
            <div class="small muted stats">
              {{ days }} dias: {{ n?.sent ?? 0 }} enviadas · {{ n?.skipped ?? 0 }} puladas · {{ n?.refused ?? 0 }} recusadas
              @if (r) {
                <br>fechadas {{ r.trades }}: {{ r.at_target }} no alvo · acertos {{ r.wins }} ·
                <b [class.up]="r.result_usd > 0" [class.down]="r.result_usd < 0">US$ {{ r.result_usd | number: '1.2-2' }}</b>
                · pior US$ {{ r.worst | number: '1.2-2' }}
              }
            </div>
          </section>
        }
      </div>

      <section class="panel" style="margin-top:16px">
        <h3>Últimas entradas</h3>
        <div class="table-wrap"><table class="small">
          <tr><th>Quando (UTC)</th><th>Mercado</th><th>Símbolo</th><th>Lado</th><th>Status</th><th>Detalhe</th></tr>
          @for (e of s.entries; track e.id) {
            <tr>
              <td class="mono" [brt]="e.created_at">{{ e.created_at | date: 'dd/MM HH:mm:ss' : 'UTC' }}</td>
              <td>{{ label(e.market) }}</td><td class="mono">{{ e.symbol }}</td>
              <td>@if (e.side) { <span class="badge" [class.buy]="e.side === 'BUY'" [class.sell]="e.side === 'SELL'">{{ e.side === 'BUY' ? 'COMPRA' : 'VENDA' }}</span> }</td>
              <td><span class="badge" [class.ok]="e.status === 'SENT'" [class.high]="e.status === 'REFUSED'">{{ statusText(e.status) }}</span></td>
              <td class="muted">{{ e.message }}</td>
            </tr>
          } @empty { <tr><td colspan="6" class="muted">Nenhuma entrada ainda.</td></tr> }
        </table></div>
      </section>
    } @else if (error()) {
      <p class="down">{{ error() }}</p>
    } @else {
      <p class="muted">Carregando…</p>
    }
  `,
  styles: `
    .head { display: flex; justify-content: space-between; align-items: center; gap: 12px; flex-wrap: wrap; }
    button.danger { border-color: var(--down); color: var(--down); }
    button.big { font-weight: 700; padding: 8px 14px; }
    .cards { display: grid; grid-template-columns: repeat(auto-fit, minmax(300px, 1fr)); gap: 12px; margin-top: 12px; }
    .card.on { border-color: var(--up); }
    .form { display: grid; grid-template-columns: 1fr 1fr; gap: 6px 10px; }
    .form label { display: flex; flex-direction: column; gap: 2px; }
    .form input, .form select { font: inherit; padding: 3px 6px; border-radius: 6px; border: 1px solid var(--border); background: var(--panel-2); color: var(--text); }
    .actions { display: flex; gap: 8px; margin: 8px 0; flex-wrap: wrap; }
    .stats { margin-top: 8px; }
  `,
})
export class AltaFrequencia implements OnInit, OnDestroy {
  private http = inject(HttpClient);
  protected names = MODEL_NAMES;
  protected markets = MARKETS;
  protected days = 7;
  protected state = signal<any>(null);
  protected error = signal<string | null>(null);
  protected forms = signal<Record<string, HfForm>>({});
  protected viab = signal<Record<string, Viab[]>>({});
  private timer?: ReturnType<typeof setInterval>;

  ngOnInit(): void {
    this.load();
    this.timer = setInterval(() => this.load(), 10_000);
  }

  ngOnDestroy(): void {
    clearInterval(this.timer);
  }

  private load(): void {
    this.http.get<any>(`/api/hf?days=${this.days}`).subscribe({
      next: (s) => { this.state.set(s); this.error.set(null); },
      error: () => this.error.set('API da alta frequência indisponível: reinicie o servidor com o jar novo.'),
    });
  }

  protected form(market: string): HfForm {
    const c: HfConfig | undefined = this.state()?.markets?.[market];
    return this.forms()[market] ?? { model: c?.model ?? 'B', horizon: c?.horizon ?? 15, lot: c?.lot ?? null,
      takeUsd: c?.takeUsd ?? 0.10, stopAtr: c?.stopAtr ?? 3 };
  }

  protected patch(market: string, p: Partial<HfForm>): void {
    this.forms.set({ ...this.forms(), [market]: { ...this.form(market), ...p } });
  }

  protected count(market: string): any {
    return (this.state()?.counts ?? []).find((x: any) => x.market === market);
  }

  protected closed(market: string): any {
    return (this.state()?.closed ?? []).find((x: any) => x.market === market);
  }

  protected label(market: string): string {
    return MARKETS.find((m) => m.code === market)?.label ?? market;
  }

  protected statusText(s: string): string {
    return ({ SENT: 'enviada', SKIPPED: 'pulada', REFUSED: 'recusada', SENDING: 'conferindo' } as Record<string, string>)[s] ?? s;
  }

  /** Onde o alvo cabe: a distância em preço do alvo com o lote, contra 2 spreads e o mínimo da corretora. */
  protected viability(market: string): void {
    const f = this.form(market);
    const lot = f.lot ?? 0.01;
    this.http.get<Viab[]>(`/api/risk/money-preview?lot=${lot}&stopUsd=${f.takeUsd}&takeUsd=${f.takeUsd}`).subscribe({
      next: (rows) => this.viab.set({ ...this.viab(), [market]: rows.filter((r) => r.market === market) }),
      error: () => {},
    });
  }

  protected start(market: string): void {
    const f = this.form(market);
    const real = this.state()?.mode === 'LIVE';
    const text = `Ligar a alta frequência em ${this.label(market)}?\n${MODEL_NAMES[f.model] ?? f.model}, ${f.horizon} min, lote ${f.lot ?? 'mínimo'}, `
      + `alvo US$ ${f.takeUsd}, stop de proteção ${f.stopAtr} ATR.\nCada previsão nova vira uma ordem por símbolo, SEM as travas de risco por ordem `
      + '(valem as técnicas e a perda do dia/semana).';
    if (real) {
      if (prompt(`${text}\n\nCONTA REAL: digite LIVE para confirmar.`) !== 'LIVE') return;
    } else if (!confirm(text)) return;
    const q = new URLSearchParams({ market, model: f.model, horizon: String(f.horizon), takeUsd: String(f.takeUsd), stopAtr: String(f.stopAtr) });
    if (f.lot) q.set('lot', String(f.lot));
    this.http.post<any>(`/api/hf/start?${q}`, {}).subscribe({ next: () => this.load(), error: (e) => alert(e?.error?.message ?? 'não consegui ligar') });
  }

  protected stop(market: string): void {
    this.http.post(`/api/hf/stop?market=${market}`, {}).subscribe(() => this.load());
  }

  protected stopAll(): void {
    if (!confirm('Parar a alta frequência em todos os mercados? (as posições abertas continuam até o alvo, o stop ou o tempo)')) return;
    this.http.post('/api/hf/stop', {}).subscribe(() => this.load());
  }
}
