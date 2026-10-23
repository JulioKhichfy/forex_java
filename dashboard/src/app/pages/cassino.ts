import { Component, OnDestroy, OnInit, computed, inject, signal } from '@angular/core';
import { DatePipe, DecimalPipe } from '@angular/common';
import { HttpClient } from '@angular/common/http';
import { MARKETS, MarketState } from '../market';
import { Brt } from '../brt';

interface Line { symbol: string; market: string; side: string; auto: boolean; lot: number | null; takeUsd: number;
  stopUsd: number; attempts: number; times: number; closeAfterMinutes: number | null; on: boolean; cycle: number;
  used: number; hits: number; status: string; message: string | null; startedAt: string | null }
interface Form { side: string; auto: boolean; lot: number | null; takeUsd: number; stopUsd: number; times: number;
  attempts: number; closeAfter: number | null }
interface Quarter { start: string; side: string | null; result: string; usd: number | null; minutes: number | null }
interface Backtest { symbol: string; lot: number; quarters: Quarter[]; message?: string; spread_usd?: number;
  spread_points?: number; too_close?: boolean; last_bar?: string }

const DEFAULT: Form = { side: 'BUY', auto: false, lot: null, takeUsd: 0.10, stopUsd: 1.00, times: 10, attempts: 1, closeAfter: null };
const STORE = 'jev.cassino.forms';

@Component({
  selector: 'page-cassino',
  imports: [DatePipe, DecimalPipe, Brt],
  template: `
    <div class="head">
      <h1>Cassino</h1>
      <div class="actions">
        <button class="danger" (click)="stopAll(false)">Parar tudo</button>
        <button class="danger big" (click)="stopAll(true)">PARAR TUDO E FECHAR AS POSIÇÕES</button>
      </div>
    </div>
    <div class="notice danger">
      <b>Ordens que você escolhe, sem as travas de risco por ordem.</b> Em cada linha: alvo e stop em US$ (com o lote da
      linha), lado (ou <b>Deixa comigo!</b>), vezes e tentativas. Ao <b>ligar</b>, a ordem sai na hora. <b>Vezes</b> = quantas
      vezes abrir, aconteça o que acontecer; em cada vez, até N <b>tentativas</b>: fechou no stop (ou por tempo), tenta de novo;
      bateu o alvo, a vez termina e começa a próxima. Total de ordens ≤ vezes × tentativas. Valem só as travas técnicas (EA, cotação, lote, mercado aberto,
      BLOCK, alvo e stop além de 2 spreads) e a <b>perda do dia/semana</b>, que para tudo. As posições do Cassino ficam fora do
      trailing e da prorrogação.
      <br><b>Repetir até acertar não cria vantagem</b>: com alvo pequeno e stop maior, a maioria das tentativas acerta, mas
      cada stop apaga vários alvos. Cada operação ainda paga o spread. O backtest abaixo mostra só a última hora; é pouco para
      dizer se a configuração presta.
      <br><b>Deixa comigo!</b>: o lado de cada ordem é o mais provável (alta × queda) na previsão mais recente do modelo B do
      símbolo, 15 min antes de 60 min, de até 75 min atrás; sem previsão recente, a linha espera. No Laboratório, entrar sempre
      no lado mais provável deu perto de zero ou negativo na maioria dos mercados: é a melhor opinião que eu tenho, não uma vantagem.
      @if (view(); as v) {
        <div class="small">Modo <b>{{ v.mode }}</b> · perda do dia {{ v.loss_limits?.maxDailyLossPct }}%, semana {{ v.loss_limits?.maxWeeklyLossPct }}%
          @if (v.blocked) { · <b class="down">BLOCK ligado: nenhuma ordem sai</b> }</div>
      }
    </div>

    @if (view(); as v) {
      @let s = v.state;
      <section class="panel">
        <h3>Global (opcional)</h3>
        <div class="global">
          <label class="small">Lucro global US$ <input type="number" min="0" step="0.10" [value]="g().takeUsd ?? ''" placeholder="desligado"
            (input)="patchGlobal({ takeUsd: num($event) })" /></label>
          <label class="small">Perda global US$ <input type="number" min="0" step="0.10" [value]="g().lossUsd ?? ''" placeholder="desligada"
            (input)="patchGlobal({ lossUsd: num($event) })" /></label>
          <label class="small">Rodadas <input type="number" min="1" step="1" [value]="g().rounds"
            (input)="patchGlobal({ rounds: num($event) ?? 1 })" /></label>
          <button class="primary" (click)="saveGlobal()">Salvar o global</button>
        </div>
        <p class="small muted">Atingiu o lucro ou a perda da rodada (fechadas + abertas do Cassino): fecha as posições do Cassino e,
          com rodadas sobrando, recomeça as linhas ligadas com as tentativas zeradas. Se as linhas terminarem sozinhas, o Cassino para.</p>
        @if (s.session) {
          <p class="small">Rodada <b>{{ s.round }}</b> de {{ s.global?.rounds ?? 1 }}
            @if (s.closing) { · <b class="down">fechando as posições da rodada</b> }
            · resultado da rodada <b [class.up]="v.round_result > 0" [class.down]="v.round_result < 0">US$ {{ v.round_result | number: '1.2-2' }}</b>
            · da sessão (fechadas) <b [class.up]="v.session_result > 0" [class.down]="v.session_result < 0">US$ {{ (v.session_result ?? 0) | number: '1.2-2' }}</b>
            @if (s.lastEvent) { <br><span class="muted">{{ s.lastEvent }}</span> }</p>
        }
      </section>

      @if (otherRunning().length) {
        <p class="small">Ligadas em outros mercados: @for (l of otherRunning(); track l.symbol) { <span class="badge ok">{{ l.symbol }}</span> }</p>
      }

      <section class="panel" style="margin-top:12px">
        <div class="head">
          <h3>{{ marketLabel() }}</h3>
          <button (click)="backtestAll()">Backtest de todos (últimos 4 quartos de hora)</button>
        </div>
        <div class="table-wrap"><table class="small grid">
          <tr><th>Símbolo</th><th>Alvo US$</th><th>Stop US$</th><th>Lote</th><th>Direção</th>
            <th title="Quantas vezes abrir, aconteça o que acontecer">Vezes</th>
            <th title="Em cada vez: até quantas ordens para bater o alvo">Tentativas</th>
            <th title="Opcional: fecha a mercado depois desses minutos (vazio = só alvo e stop)">Saída (min)</th><th></th><th>Estado</th><th>Backtest</th></tr>
          @for (sym of symbols(); track sym) {
            @let l = s.lines[sym];
            @let f = form(sym);
            @let running = l?.status === 'RUNNING';
            <tr [class.on]="running">
              <td class="mono"><b>{{ sym }}</b></td>
              <td><input type="number" min="0.01" step="0.05" [value]="f.takeUsd" [disabled]="running" (input)="patch(sym, { takeUsd: num($event) ?? 0 })" /></td>
              <td><input type="number" min="0.01" step="0.10" [value]="f.stopUsd" [disabled]="running" (input)="patch(sym, { stopUsd: num($event) ?? 0 })" /></td>
              <td><input type="number" min="0.01" step="0.01" [value]="f.lot ?? ''" placeholder="mínimo" [disabled]="running" (input)="patch(sym, { lot: num($event) })" /></td>
              <td class="dir">
                <select [value]="f.side" [disabled]="running || f.auto" [class.hidden]="f.auto" (change)="patch(sym, { side: $any($event.target).value })">
                  <option value="BUY">Compra</option><option value="SELL">Venda</option></select>
                <label class="auto" title="O sistema decide o lado de cada ordem pela previsão mais recente do modelo B">
                  <input type="checkbox" [checked]="f.auto" [disabled]="running" (change)="patch(sym, { auto: $any($event.target).checked })" />
                  Deixa comigo!</label>
              </td>
              <td><input type="number" min="1" step="1" [value]="f.times" [disabled]="running" (input)="patch(sym, { times: num($event) ?? 1 })" /></td>
              <td><input type="number" min="1" step="1" [value]="f.attempts" [disabled]="running" (input)="patch(sym, { attempts: num($event) ?? 1 })" /></td>
              <td><input type="number" min="1" step="1" [value]="f.closeAfter ?? ''" placeholder="—" [disabled]="running" (input)="patch(sym, { closeAfter: num($event) })" /></td>
              <td>@if (running) { <button class="danger" (click)="stop(sym)">Parar</button> }
                @else { <button class="primary" [disabled]="s.closing" (click)="start(sym)">Ligar</button> }</td>
              <td class="state">
                @if (l) {
                  <span class="badge" [class.ok]="l.status === 'RUNNING' || l.status === 'DONE'" [class.high]="l.status === 'STOPPED' || l.status === 'EXHAUSTED'">{{ statusText(l.status) }}</span>
                  @if (l.status !== 'OFF') {
                    <span class="muted"> vez {{ l.cycle || 1 }}/{{ l.times || 1 }} · tentativa {{ l.used }}/{{ l.attempts }} · alvos {{ l.hits || 0 }}</span>
                  }
                  @if (l.message) { <div class="muted">{{ l.message }}</div> }
                }
              </td>
              <td class="bt">
                <button (click)="backtest(sym)">4 quartos</button>
                @if (bt()[sym]; as b) {
                  @if (b.message) { <div class="muted">{{ b.message }}</div> }
                  <div class="quarters">
                    @for (q of b.quarters; track q.start) {
                      <span class="badge" [class.ok]="q.result === 'ALVO'" [class.high]="q.result === 'STOP'"
                        [title]="(q.minutes ?? '—') + ' min'"><span [brt]="q.start">{{ q.start | date: 'HH:mm' : 'UTC' }}</span>
                        @if (f.auto && q.side) { {{ q.side === 'BUY' ? 'C' : 'V' }} }
                        {{ resultText(q.result) }} @if (q.usd !== null) { {{ q.usd | number: '1.2-2' }} }</span>
                    }
                  </div>
                  @if (b.spread_usd !== undefined) {
                    <div class="muted">lote {{ b.lot }} · spread {{ b.spread_points }} pts = US$ {{ b.spread_usd | number: '1.2-2' }}
                      @if (b.too_close) { · <b class="down">alvo ou stop dentro de 2 spreads: a ordem é recusada</b> }</div>
                  }
                }
              </td>
            </tr>
          } @empty { <tr><td colspan="11" class="muted">Nenhum símbolo deste mercado com especificação do MT5.</td></tr> }
        </table></div>
        <p class="small muted">Backtest: se a linha tivesse aberto no início de cada um dos últimos 4 quartos de hora completos
          (UTC), com o spread de agora — alvo, stop, tempo ou ainda aberta. Alvo e stop no mesmo minuto contam como stop. Com
          <b>Deixa comigo!</b>, cada quarto usa o lado que a previsão daquela hora daria (C = compra, V = venda).</p>
      </section>

      <section class="panel" style="margin-top:16px">
        <h3>Últimas tentativas</h3>
        @if (v.totals; as t) {
          <p class="small">7 dias: {{ t.trades ?? 0 }} fechadas · {{ t.at_target ?? 0 }} no alvo ·
            <b [class.up]="t.result_usd > 0" [class.down]="t.result_usd < 0">US$ {{ (t.result_usd ?? 0) | number: '1.2-2' }}</b>
            @if (t.worst !== null) { · pior US$ {{ t.worst | number: '1.2-2' }} }</p>
        }
        <div class="table-wrap"><table class="small">
          <tr><th>Quando (UTC)</th><th>Rodada</th><th>Símbolo</th><th>Vez</th><th>Tentativa</th><th>Lado</th><th>Status</th><th class="right">US$</th><th>Detalhe</th></tr>
          @for (e of v.entries; track e.id) {
            <tr>
              <td class="mono" [brt]="e.created_at">{{ e.created_at | date: 'dd/MM HH:mm:ss' : 'UTC' }}</td>
              <td>{{ e.round }}</td><td class="mono">{{ e.symbol }}</td><td>{{ e.cycle ?? '' }}</td><td>{{ e.attempt }}</td>
              <td><span class="badge" [class.buy]="e.side === 'BUY'" [class.sell]="e.side === 'SELL'">{{ e.side === 'BUY' ? 'COMPRA' : 'VENDA' }}</span>
                @if (e.auto_side) { <span class="muted" title="Lado escolhido pelo sistema (Deixa comigo!)"> auto</span> }</td>
              <td><span class="badge" [class.ok]="e.status === 'TARGET'" [class.high]="e.status === 'REFUSED' || e.status === 'FAILED'">{{ entryText(e.status) }}</span></td>
              <td class="right mono" [class.up]="e.result_usd > 0" [class.down]="e.result_usd < 0">{{ e.result_usd === null ? '' : (e.result_usd | number: '1.2-2') }}</td>
              <td class="muted">{{ e.message }}</td>
            </tr>
          } @empty { <tr><td colspan="9" class="muted">Nenhuma tentativa ainda.</td></tr> }
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
    .actions { display: flex; gap: 8px; flex-wrap: wrap; }
    button.danger { border-color: var(--down); color: var(--down); }
    button.big { font-weight: 700; padding: 8px 14px; }
    .global { display: flex; gap: 10px; align-items: flex-end; flex-wrap: wrap; }
    .global label { display: flex; flex-direction: column; gap: 2px; }
    input, select { font: inherit; padding: 3px 6px; border-radius: 6px; border: 1px solid var(--border); background: var(--panel-2); color: var(--text); }
    .grid input { width: 76px; }
    .grid select { width: 90px; }
    tr.on td { background: color-mix(in srgb, var(--up) 8%, transparent); }
    td.state { min-width: 150px; }
    td.bt { min-width: 220px; }
    .quarters { display: flex; gap: 4px; flex-wrap: wrap; margin-top: 4px; }
    td.dir { min-width: 120px; }
    td.dir select.hidden { display: none; }
    label.auto { display: flex; align-items: center; gap: 4px; white-space: nowrap; margin-top: 2px; }
    label.auto input { width: auto; }
  `,
})
export class Cassino implements OnInit, OnDestroy {
  private http = inject(HttpClient);
  private marketState = inject(MarketState);
  protected view = signal<any>(null);
  protected error = signal<string | null>(null);
  protected forms = signal<Record<string, Form>>(this.loadForms());
  protected bt = signal<Record<string, Backtest>>({});
  protected globalForm = signal<{ takeUsd: number | null; lossUsd: number | null; rounds: number } | null>(null);
  private timer?: ReturnType<typeof setInterval>;

  protected symbols = computed<string[]>(() => this.view()?.symbols?.[this.marketState.market()] ?? []);
  protected marketLabel = computed(() => MARKETS.find((m) => m.code === this.marketState.market())?.label ?? this.marketState.market());
  protected otherRunning = computed<Line[]>(() => Object.values<Line>(this.view()?.state?.lines ?? {})
    .filter((l) => l.status === 'RUNNING' && l.market !== this.marketState.market()));
  protected g = computed(() => this.globalForm() ?? (() => {
    const s = this.view()?.state?.global;
    return { takeUsd: s?.takeUsd ?? null, lossUsd: s?.lossUsd ?? null, rounds: s?.rounds ?? 1 };
  })());

  ngOnInit(): void {
    this.load();
    this.timer = setInterval(() => this.load(), 3_000);
  }

  ngOnDestroy(): void {
    clearInterval(this.timer);
  }

  private load(): void {
    this.http.get<any>('/api/casino').subscribe({
      next: (v) => { this.view.set(v); this.error.set(null); },
      error: () => this.error.set('API do Cassino indisponível: reinicie o servidor com o jar novo.'),
    });
  }

  protected num(ev: Event): number | null {
    const v = (ev.target as HTMLInputElement).value;
    return v === '' ? null : +v;
  }

  protected form(sym: string): Form {
    const l: Line | undefined = this.view()?.state?.lines?.[sym];
    const saved = this.forms()[sym];
    if (saved && l?.status !== 'RUNNING') return { ...DEFAULT, ...saved };   // ligada: mostra o que está valendo
    return l ? { side: l.auto ? 'BUY' : l.side, auto: !!l.auto, lot: l.lot, takeUsd: l.takeUsd, stopUsd: l.stopUsd,
      times: l.times || 1, attempts: l.attempts, closeAfter: l.closeAfterMinutes } : DEFAULT;
  }

  protected patch(sym: string, p: Partial<Form>): void {
    this.forms.set({ ...this.forms(), [sym]: { ...this.form(sym), ...p } });
    try { localStorage.setItem(STORE, JSON.stringify(this.forms())); } catch { /* só nesta aba */ }
  }

  private loadForms(): Record<string, Form> {
    try { return JSON.parse(localStorage.getItem(STORE) ?? '{}'); } catch { return {}; }
  }

  protected patchGlobal(p: Partial<{ takeUsd: number | null; lossUsd: number | null; rounds: number }>): void {
    this.globalForm.set({ ...this.g(), ...p });
  }

  protected saveGlobal(): void {
    const g = this.g();
    const q = new URLSearchParams({ rounds: String(g.rounds || 1) });
    if (g.takeUsd) q.set('takeUsd', String(g.takeUsd));
    if (g.lossUsd) q.set('lossUsd', String(g.lossUsd));
    this.http.post<any>(`/api/casino/global?${q}`, {}).subscribe({
      next: () => { this.globalForm.set(null); this.load(); },
      error: (e) => alert(e?.error?.message ?? 'não consegui salvar o global'),
    });
  }

  protected start(sym: string): void {
    const f = this.form(sym);
    const side = f.auto ? 'lado pela previsão (Deixa comigo!)' : f.side === 'BUY' ? 'COMPRA' : 'VENDA';
    const text = `Ligar ${sym}: ${side}, lote ${f.lot ?? 'mínimo'}, alvo US$ ${f.takeUsd}, stop US$ ${f.stopUsd}, `
      + `${f.times} vez(es) × até ${f.attempts} tentativa(s)${f.closeAfter ? `, sai em ${f.closeAfter} min` : ''}?\n`
      + `Até ${f.times * f.attempts} ordens. A primeira sai AGORA, sem as travas de risco por ordem (valem as técnicas e a perda do dia/semana).`;
    if (this.view()?.mode === 'LIVE') {
      if (prompt(`${text}\n\nCONTA REAL: digite LIVE para confirmar.`) !== 'LIVE') return;
    } else if (!confirm(text)) return;
    const q = new URLSearchParams({ symbol: sym, side: f.side, auto: String(!!f.auto), takeUsd: String(f.takeUsd),
      stopUsd: String(f.stopUsd), times: String(f.times || 1), attempts: String(f.attempts || 1) });
    if (f.lot) q.set('lot', String(f.lot));
    if (f.closeAfter) q.set('closeAfter', String(f.closeAfter));
    this.http.post<any>(`/api/casino/start?${q}`, {}).subscribe({
      next: () => this.load(),
      error: (e) => alert(e?.error?.message ?? 'não consegui ligar'),
    });
  }

  protected stop(sym: string): void {
    this.http.post(`/api/casino/stop?symbol=${sym}`, {}).subscribe(() => this.load());
  }

  protected stopAll(close: boolean): void {
    const text = close ? 'Parar todas as linhas e FECHAR todas as posições abertas do Cassino a mercado?'
      : 'Parar todas as linhas? (as posições abertas continuam com o alvo e o stop no servidor)';
    if (!confirm(text)) return;
    this.http.post(`/api/casino/stop-all?close=${close}`, {}).subscribe(() => this.load());
  }

  protected backtest(sym: string): void {
    const f = this.form(sym);
    const q = new URLSearchParams({ symbol: sym, side: f.side, auto: String(!!f.auto), takeUsd: String(f.takeUsd),
      stopUsd: String(f.stopUsd) });
    if (f.lot) q.set('lot', String(f.lot));
    if (f.closeAfter) q.set('closeAfter', String(f.closeAfter));
    this.http.get<Backtest>(`/api/casino/backtest?${q}`).subscribe({
      next: (b) => this.bt.set({ ...this.bt(), [sym]: b }),
      error: (e) => this.bt.set({ ...this.bt(), [sym]: { symbol: sym, lot: 0, quarters: [], message: e?.error?.message ?? 'backtest indisponível' } }),
    });
  }

  protected backtestAll(): void {
    for (const s of this.symbols()) this.backtest(s);
  }

  protected statusText(s: string): string {
    return ({ OFF: 'desligada', RUNNING: 'ligada', DONE: 'terminou', TARGET: 'bateu o alvo', EXHAUSTED: 'sem tentativas',
      STOPPED: 'parada', GLOBAL: 'global' } as Record<string, string>)[s] ?? s;
  }

  protected entryText(s: string): string {
    return ({ SENT: 'aberta', REFUSED: 'recusada', TARGET: 'alvo', CLOSED: 'fechada', FAILED: 'falhou' } as Record<string, string>)[s] ?? s;
  }

  protected resultText(r: string): string {
    return ({ ALVO: 'alvo', STOP: 'stop', TEMPO: 'tempo', ABERTA: 'aberta', SEM_DADOS: 'sem dados',
      SEM_LADO: 'sem previsão' } as Record<string, string>)[r] ?? r;
  }
}
