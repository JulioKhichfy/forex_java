import { Component, OnDestroy, OnInit, computed, inject, signal } from '@angular/core';
import { DatePipe, DecimalPipe } from '@angular/common';
import { HttpClient } from '@angular/common/http';

const KEY = 'jev.pizza';

/** Compra Pizza (28/10/2026): o operador define a pizza e o stop; o sistema joga até um dos dois. */
@Component({
  selector: 'page-pizza',
  imports: [DatePipe, DecimalPipe],
  template: `
    <div class="head">
      <h1>🍕 Compra Pizza</h1>
      @if (data(); as d) { <span class="small">Modo <b>{{ d.mode }}</b> @if (d.blocked) { · <b class="down">BLOCK ligado</b> }</span> }
    </div>
    <div class="notice">
      Você diz quanto custa a pizza e até quanto aceita perder. Ao <b>JOGAR</b>, eu abro as operações em que mais confio — primeiro
      o <b>sinal da regra do mercado</b>, depois a <b>previsão forte</b> do modelo B (sem LATERAL) — até
      {{ data()?.config?.maxPositions ?? 5 }} ao mesmo tempo, com lote mínimo e o stop/alvo do mercado. Quando o resultado da
      partida (fechadas + abertas) chegar na pizza ou no stop, fecho tudo.
      <div class="small muted" style="margin-top:6px">Travas como no Cassino (sem as de risco por ordem); valem as técnicas e a perda
        do dia/semana. É jogo: a melhor regra deu ~+0,1 R por operação nos testes — não é garantia de pizza.</div>
    </div>

    @if (data(); as d) {
      @let g = d.game;
      <div class="board">
        <div class="pizza" [style.background]="pizzaFill()" [class.won]="g?.status === 'PIZZA'" [class.lost]="g?.status === 'NO_PIZZA'">
          <div class="slices"></div>
          <div class="center">
            @if (g) {
              <b [class.up]="res() > 0" [class.down]="res() < 0">US$ {{ res() | number: '1.2-2' }}</b>
              <span class="small muted">de US$ {{ +g.pizza_usd | number: '1.2-2' }}</span>
            } @else { <span class="muted">sem partida</span> }
          </div>
        </div>

        <div class="side">
          @if (g?.live) {
            <h3>{{ statusText(g.status) }}</h3>
            <p>{{ g.message }}</p>
            <div class="bar">
              <div class="zero" [style.left.%]="zeroPct()"></div>
              <div class="mark" [style.left.%]="resPct()" [class.up]="res() >= 0" [class.down]="res() < 0"></div>
            </div>
            <div class="small barlbl"><span class="down">stop −US$ {{ +g.stop_usd | number: '1.2-2' }}</span><span>0</span>
              <span class="up">pizza US$ {{ +g.pizza_usd | number: '1.2-2' }}</span></div>
            <p class="small muted">Começou {{ g.started_at | date: 'HH:mm' : '-0300' }} (Brasília) ·
              {{ openCount() }} posição(ões) aberta(s) · {{ tradesCount() }} operação(ões)</p>
            <button class="danger" [disabled]="busy() || g.status !== 'RUNNING'" (click)="stop()">Desistir (fechar tudo)</button>
          } @else {
            @if (g) {
              <h3 [class.up]="g.status === 'PIZZA'" [class.down]="g.status === 'NO_PIZZA'">{{ g.message }}</h3>
            }
            <div class="form">
              <label>Valor da pizza (US$)
                <input type="number" min="0.01" step="0.05" [value]="pizzaUsd()" (input)="setPizza($event)" /></label>
              <label>Stop — não vou comer pizza (US$)
                <input type="number" min="0.01" step="0.05" [value]="stopUsd()" (input)="setStop($event)" /></label>
              <button class="primary big" [disabled]="busy() || d.blocked || !(pizzaUsd() > 0) || !(stopUsd() > 0)" (click)="play()">
                JOGAR</button>
            </div>
            @if (msg()) { <p class="down small">{{ msg() }}</p> }
          }
        </div>
      </div>

      @if (d.entries?.length) {
        <section class="panel">
          <h3>Operações da partida</h3>
          <table class="small">
            <tr><th>Hora</th><th>Símbolo</th><th>Lado</th><th>Por quê</th><th>Situação</th><th class="right">US$</th></tr>
            @for (e of d.entries; track e.id) {
              <tr [class.dim]="e.status === 'REFUSED' || e.status === 'FAILED'">
                <td class="mono">{{ e.created_at | date: 'HH:mm' : '-0300' }}</td>
                <td class="mono">{{ e.symbol }}</td>
                <td><span class="badge" [class.buy]="e.side === 'BUY'" [class.sell]="e.side === 'SELL'">{{ e.side === 'BUY' ? 'COMPRA' : 'VENDA' }}</span></td>
                <td>@if (e.tier === 'SIGNAL') { <span class="badge ok">sinal</span> } {{ e.reason }}</td>
                <td>{{ entryText(e) }}</td>
                <td class="right mono">
                  @if (e.result_usd != null) { <b [class.up]="+e.result_usd > 0" [class.down]="+e.result_usd < 0">{{ +e.result_usd | number: '1.2-2' }}</b> }
                  @else if (e.floating_usd != null) { <span [class.up]="e.floating_usd > 0" [class.down]="e.floating_usd < 0">{{ e.floating_usd | number: '1.2-2' }}</span> <span class="muted">aberta</span> }
                </td>
              </tr>
            }
          </table>
        </section>
      }

      <section class="panel">
        <h3>De olho agora</h3>
        @if (d.candidates?.length) {
          <p class="small muted">O que eu abriria neste momento, do que mais confio para o que menos (uma por símbolo; cada previsão
            só uma vez por partida).</p>
          <table class="small">
            <tr><th>Símbolo</th><th>Lado</th><th>Por quê</th></tr>
            @for (c of d.candidates.slice(0, 10); track c.symbol) {
              <tr><td class="mono">{{ c.symbol }}</td>
                <td><span class="badge" [class.buy]="c.side === 'BUY'" [class.sell]="c.side === 'SELL'">{{ c.side === 'BUY' ? 'COMPRA' : 'VENDA' }}</span></td>
                <td>@if (c.tier === 'SIGNAL') { <span class="badge ok">sinal</span> } {{ c.reason }}</td></tr>
            }
          </table>
        } @else {
          <p class="small muted">Nada em que eu confie agora: as previsões saem na hora cheia (e 2 min depois dos eventos), de segunda a
            sexta, 00:00–20:00 UTC. Numa partida, eu espero a próxima.</p>
        }
      </section>

      @if (d.history?.length) {
        <section class="panel">
          <h3>Partidas</h3>
          <table class="small">
            <tr><th>Início</th><th class="right">Pizza</th><th class="right">Stop</th><th class="right">Operações</th><th>Resultado</th></tr>
            @for (h of d.history; track h.id) {
              <tr><td class="mono">{{ h.started_at | date: 'dd/MM HH:mm' : '-0300' }}</td>
                <td class="right mono">{{ +h.pizza_usd | number: '1.2-2' }}</td><td class="right mono">{{ +h.stop_usd | number: '1.2-2' }}</td>
                <td class="right mono">{{ h.trades }}</td>
                <td [class.up]="h.status === 'PIZZA'" [class.down]="h.status === 'NO_PIZZA'">
                  {{ h.status === 'PIZZA' ? '🍕 ' : '' }}{{ h.message }}</td></tr>
            }
          </table>
          <p class="small muted">{{ score() }}</p>
        </section>
      }
    } @else if (error()) { <p class="down">{{ error() }}</p> } @else { <p class="muted">Carregando…</p> }
  `,
  styles: `
    .head { display: flex; justify-content: space-between; align-items: center; gap: 12px; flex-wrap: wrap; }
    .board { display: flex; gap: 24px; align-items: center; flex-wrap: wrap; margin: 16px 0; }
    .pizza { position: relative; width: 220px; height: 220px; border-radius: 50%; border: 10px solid #c8873a;
      box-shadow: 0 4px 16px rgba(0,0,0,.25); flex: none; }
    .pizza.won { animation: pulse 1.2s ease-in-out 3; }
    .pizza.lost { filter: grayscale(.6); }
    @keyframes pulse { 50% { transform: scale(1.06); } }
    .slices { position: absolute; inset: 0; border-radius: 50%;
      background: repeating-conic-gradient(from 0deg, transparent 0deg 44deg, rgba(120,60,10,.55) 44deg 45deg); }
    .center { position: absolute; inset: 62px; border-radius: 50%; background: var(--panel); display: flex;
      flex-direction: column; align-items: center; justify-content: center; text-align: center; box-shadow: 0 0 0 3px rgba(0,0,0,.08); }
    .center b { font-size: 20px; }
    .side { flex: 1; min-width: 280px; }
    .form { display: flex; gap: 12px; align-items: flex-end; flex-wrap: wrap; }
    .form label { display: flex; flex-direction: column; gap: 4px; font-size: 13px; }
    input { font: inherit; padding: 6px 8px; border-radius: 6px; border: 1px solid var(--border); background: var(--panel-2);
      color: var(--text); width: 140px; }
    button.big { font-size: 18px; padding: 10px 28px; font-weight: 700; letter-spacing: 1px; }
    button.danger { border-color: var(--down); color: var(--down); }
    .bar { position: relative; height: 14px; border-radius: 7px; margin: 8px 0 2px;
      background: linear-gradient(90deg, var(--down) 0%, var(--panel-2) 50%, var(--up) 100%); opacity: .9; }
    .bar .zero { position: absolute; top: -3px; bottom: -3px; width: 2px; background: var(--text); opacity: .5; }
    .bar .mark { position: absolute; top: -5px; width: 12px; height: 24px; margin-left: -6px; border-radius: 4px;
      background: var(--text); border: 2px solid var(--panel); }
    .barlbl { display: flex; justify-content: space-between; }
    .panel { margin-top: 12px; }
    tr.dim td { opacity: .55; }
  `,
})
export class Pizza implements OnInit, OnDestroy {
  private http = inject(HttpClient);
  protected data = signal<any>(null);
  protected error = signal<string | null>(null);
  protected busy = signal(false);
  protected msg = signal<string | null>(null);
  protected pizzaUsd = signal<number>(this.saved().pizza ?? 0.5);
  protected stopUsd = signal<number>(this.saved().stop ?? 0.5);
  private timer?: ReturnType<typeof setInterval>;

  protected res = computed<number>(() => +(this.data()?.game?.result_now ?? 0));
  protected openCount = computed(() => (this.data()?.entries ?? []).filter((e: any) => e.status === 'SENT').length);
  protected tradesCount = computed(() => (this.data()?.entries ?? []).filter((e: any) => e.order_id != null).length);

  /** A pizza enche (laranja) até o valor dela; no prejuízo, o vermelho cresce até o stop. */
  protected pizzaFill = computed<string>(() => {
    const g = this.data()?.game;
    if (!g) return '#f3d9a4';
    const r = this.res();
    if (r >= 0) {
      const f = Math.min(1, r / +g.pizza_usd) * 360;
      return `conic-gradient(#f2a33a 0deg ${f}deg, #f6e3bb ${f}deg 360deg)`;
    }
    const f = Math.min(1, -r / +g.stop_usd) * 360;
    return `conic-gradient(#d9534f 0deg ${f}deg, #f6e3bb ${f}deg 360deg)`;
  });

  /** Posição na barra: −stop = 0%, 0 = zeroPct, pizza = 100%. */
  protected resPct = computed<number>(() => {
    const g = this.data()?.game;
    if (!g) return 50;
    const lo = -+g.stop_usd, hi = +g.pizza_usd;
    return Math.max(0, Math.min(100, (this.res() - lo) / (hi - lo) * 100));
  });
  protected zeroPct = computed<number>(() => {
    const g = this.data()?.game;
    return g ? +g.stop_usd / (+g.stop_usd + +g.pizza_usd) * 100 : 50;
  });

  protected score = computed<string>(() => {
    const h = (this.data()?.history ?? []).filter((x: any) => ['PIZZA', 'NO_PIZZA', 'STOPPED'].includes(x.status));
    if (!h.length) return '';
    const won = h.filter((x: any) => x.status === 'PIZZA').length;
    const sum = h.reduce((a: number, x: any) => a + (+x.result_usd || 0), 0);
    return `Placar: ${won} pizza(s) em ${h.length} partida(s) · resultado somado US$ ${sum.toFixed(2)}`;
  });

  ngOnInit(): void {
    this.load();
    this.timer = setInterval(() => this.load(), 5_000);
  }

  ngOnDestroy(): void {
    clearInterval(this.timer);
  }

  private load(): void {
    this.http.get<any>('/api/pizza').subscribe({
      next: (d) => { this.data.set(d); this.error.set(null); },
      error: () => this.error.set('API indisponível: reinicie o servidor com o jar novo.'),
    });
  }

  protected setPizza(ev: Event): void { this.pizzaUsd.set(+(ev.target as HTMLInputElement).value); this.persist(); }
  protected setStop(ev: Event): void { this.stopUsd.set(+(ev.target as HTMLInputElement).value); this.persist(); }

  private saved(): { pizza?: number; stop?: number } {
    try { return JSON.parse(localStorage.getItem(KEY) ?? '{}'); } catch { return {}; }
  }

  private persist(): void {
    try { localStorage.setItem(KEY, JSON.stringify({ pizza: this.pizzaUsd(), stop: this.stopUsd() })); } catch { /* só nesta aba */ }
  }

  protected play(): void {
    const text = `Jogar: pizza de US$ ${this.pizzaUsd().toFixed(2)}, stop de US$ ${this.stopUsd().toFixed(2)}.\n`
      + 'Eu abro as operações sozinho (sem as travas de risco por ordem) até um dos dois.';
    if (!this.confirmLive(text)) return;
    this.busy.set(true);
    this.msg.set(null);
    this.http.post<any>(`/api/pizza/start?pizzaUsd=${this.pizzaUsd()}&stopUsd=${this.stopUsd()}`, {}).subscribe({
      next: () => { this.busy.set(false); this.load(); },
      error: (e) => { this.busy.set(false); this.msg.set(e?.error?.message ?? 'não consegui começar'); },
    });
  }

  protected stop(): void {
    if (!confirm('Desistir da pizza? Fecho todas as posições da partida a mercado.')) return;
    this.busy.set(true);
    this.http.post('/api/pizza/stop', {}).subscribe({ next: () => { this.busy.set(false); this.load(); }, error: () => this.busy.set(false) });
  }

  private confirmLive(text: string): boolean {
    if (this.data()?.mode === 'LIVE') return prompt(`${text}\n\nCONTA REAL: digite LIVE para confirmar.`) === 'LIVE';
    return confirm(text);
  }

  protected statusText(s: string): string {
    return s === 'RUNNING' ? 'Caçando a pizza…' : s === 'CLOSING_PIZZA' ? 'Pizza garantida! Fechando…'
      : s === 'CLOSING_NO_PIZZA' ? 'Stop da partida: fechando…' : s === 'CLOSING_STOPPED' ? 'Desistindo: fechando…' : s;
  }

  protected entryText(e: any): string {
    if (e.status === 'SENT') return e.order_status === 'FILLED' ? 'aberta · ' + e.message : 'enviando · ' + e.message;
    if (e.status === 'DONE') return e.message;
    return e.message ?? e.status;
  }
}
