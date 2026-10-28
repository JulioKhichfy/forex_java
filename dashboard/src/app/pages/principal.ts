import { Component, OnDestroy, OnInit, computed, inject, signal } from '@angular/core';
import { DatePipe, DecimalPipe } from '@angular/common';
import { HttpClient } from '@angular/common/http';
import { Brt } from '../brt';

interface Ev { code: string; name: string; actual: number | null; forecast: number | null; previous: number | null;
  impact: string | null; good_sign: number | null; consensus: number | null }
interface Sym { symbol: string; market: string; dir: number; n?: number; nPos?: number; nNeg?: number; abs15?: number;
  abs60?: number; upPos15?: number | null; upNeg15?: number | null; spreadAtr?: number }
interface Cluster { key: string; currency: string; at: string; events: Ev[]; main: string; released: boolean; symbols: Sym[] }
interface Settings { lot: number | null; stopUsd: number; takeUsd: number; closeAfter: number | null; autoMode: string }

const KEY = 'jev.principal';
const DEFAULTS: Settings = { lot: null, stopUsd: 1.0, takeUsd: 1.0, closeAfter: 60, autoMode: 'MODEL' };

@Component({
  selector: 'page-principal',
  imports: [DatePipe, DecimalPipe, Brt],
  template: `
    <div class="head">
      <h1>Principal de hoje</h1>
      <span class="muted small">agora {{ now() | date: 'HH:mm' : 'UTC' }} UTC · {{ now() | date: 'HH:mm' : '-0300' }} Brasília</span>
    </div>
    <div class="notice">
      Os eventos de <b>importância alta</b> das próximas 48 h, de todas as moedas, com o que o calendário e o histórico dizem.
      Em cada um você pode <b>operar automaticamente quando sair</b> ou <b>operar agora, no lado do consenso</b>.
      As ordens daqui <b>não passam pelas travas de risco por ordem</b> (como o Cassino); valem as técnicas (EA, cotação,
      lote, alvo e stop além de 2 spreads) e a <b>perda do dia/semana</b>. Stop e alvo em US$ por ordem.
      <div class="evidence small">
        <div><b>Pelo modelo</b> (espera a previsão do modelo B feita 2 min depois do evento, pula LATERAL): é o que deu
          vantagem no <b>ouro</b> (regra do ouro) e nos <b>índices</b>; no forex, sem vantagem comprovada.</div>
        <div><b>Pelo dado</b> (resultado melhor que o previsto para a moeda = a favor dela): <b class="down">−0,15 ATR por operação</b>
          no estudo de 2022–2026 — o preço já anda 0,35–0,40 ATR nos primeiros segundos, antes de conseguirmos entrar.</div>
        <div><b>Consenso, antes</b> (entra já, no lado que o previsto implica): <b>não testado</b>. Quando o consenso se
          confirma, o preço costuma já estar ajustado — no BoC de 28/10 (alta esperada), o USDCAD foi 15 pips e voltou em 30 s.</div>
      </div>
      @if (data(); as d) { <div class="small">Modo <b>{{ d.mode }}</b> @if (d.blocked) { · <b class="down">BLOCK ligado: nenhuma ordem sai</b> }</div> }
    </div>

    @if (calendarStale()) {
      <div class="notice danger"><b>O calendário do MT5 não está trazendo os resultados</b>: {{ calendarStale() }} já passou
        sem o valor real. Sem ele, "pelo dado" expira e os modelos ficam sem a surpresa do evento. No MT5: Ferramentas →
        Opções → Servidor → "Ativar notícias", e reinicie o terminal.</div>
    }

    <section class="panel settings">
      <h3>Ordens desta página</h3>
      <label class="small">Lote <input type="number" min="0.01" step="0.01" [value]="s().lot ?? ''" placeholder="mínimo" (input)="patch({ lot: num($event) })" /></label>
      <label class="small">Stop US$ <input type="number" min="0.01" step="0.25" [value]="s().stopUsd" (input)="patch({ stopUsd: num($event) ?? 0 })" /></label>
      <label class="small">Alvo US$ <input type="number" min="0.01" step="0.25" [value]="s().takeUsd" (input)="patch({ takeUsd: num($event) ?? 0 })" /></label>
      <label class="small">Saída (min) <input type="number" min="1" step="5" [value]="s().closeAfter ?? ''" placeholder="—" (input)="patch({ closeAfter: num($event) })" /></label>
      <label class="small">Automático
        <select [value]="s().autoMode" (change)="patch({ autoMode: $any($event.target).value })">
          <option value="MODEL">pelo modelo (depois do evento)</option>
          <option value="SURPRISE">pelo dado (resultado × previsto)</option>
        </select></label>
    </section>

    @if (data(); as d) {
      @for (c of upcoming(); track c.key) {
        <section class="panel ev" [class.soon]="minutesTo(c) <= 60">
          <div class="evhead">
            <span class="when"><b>{{ c.at | date: 'HH:mm' : '-0300' }}</b> Brasília · {{ c.at | date: 'HH:mm' : 'UTC' }} UTC
              · <span [class.down]="minutesTo(c) <= 30">{{ countdown(c) }}</span></span>
            <span class="badge cur">{{ c.currency }}</span>
            <span class="muted small">{{ c.at | date: 'dd/MM' : '-0300' }}</span>
          </div>
          <table class="small evs">
            <tr><th>Evento</th><th class="right">Previsto</th><th class="right">Anterior</th><th class="right">Real</th><th>Consenso para {{ c.currency }}</th></tr>
            @for (e of c.events; track e.code) {
              <tr [class.main]="e.code === c.main">
                <td>{{ e.name }} @if (e.code === c.main) { <span class="badge" title="O evento que decide no modo 'pelo dado'">principal</span> }</td>
                <td class="right mono">{{ e.forecast ?? '—' }}</td><td class="right mono">{{ e.previous ?? '—' }}</td>
                <td class="right mono" [class.up]="e.impact === 'POSITIVE'" [class.down]="e.impact === 'NEGATIVE'">{{ e.actual ?? '' }}</td>
                <td class="small">{{ consensusText(e, c.currency) }}</td>
              </tr>
            }
          </table>

          <div class="syms">
            @for (x of c.symbols; track x.symbol) {
              <label class="sym" [class.on]="picked(c, x.symbol)" [title]="histTitle(x)">
                <input type="checkbox" [checked]="picked(c, x.symbol)" (change)="toggle(c, x.symbol)" />
                <span class="mono">{{ x.symbol }}</span>
                @if (x.abs15 != null) { <span class="muted"> {{ x.abs15 | number: '1.1-1' }} ATR</span> }
                @if (x.dir === 0) { <span class="muted" title="Sem regra de lado pelo dado ou pelo consenso: só o modo modelo"> · só modelo</span> }
              </label>
            }
          </div>
          <p class="small muted hist">{{ historyLine(c) }}</p>

          <div class="actions">
            @let plan = armedPlan(c);
            @if (plan) {
              <span class="badge ok">automático armado · {{ plan.mode === 'MODEL' ? 'pelo modelo' : 'pelo dado' }}</span>
              <button class="danger" (click)="cancel(plan.id)">Desarmar</button>
            } @else {
              <button class="primary" [disabled]="!pickedList(c).length" (click)="arm(c)">
                Operar automaticamente quando sair ({{ s().autoMode === 'MODEL' ? 'pelo modelo' : 'pelo dado' }})</button>
            }
            @let cs = consensusSide(c);
            @if (cs) {
              <button [disabled]="!pickedList(c).length" (click)="operateNow(c, cs)">
                Operar agora (como o consenso esperava: {{ cs > 0 ? 'a favor de ' : 'contra ' }}{{ c.currency }})</button>
            } @else {
              <span class="small muted">sem consenso no calendário —</span>
              <button [disabled]="!pickedList(c).length" (click)="operateNow(c, 1)">Agora: a favor de {{ c.currency }}</button>
              <button [disabled]="!pickedList(c).length" (click)="operateNow(c, -1)">Agora: contra {{ c.currency }}</button>
            }
          </div>
          @for (p of plansOf(c); track p.id) {
            <div class="plan small">
              <b>{{ modeText(p.mode) }}</b> · {{ statusText(p.status) }} · {{ p.message }}
              @for (o of ordersOf(p.id); track o.symbol) {
                <div class="po"><span class="mono">{{ o.symbol }}</span>
                  @if (o.side) { <span class="badge" [class.buy]="o.side === 'BUY'" [class.sell]="o.side === 'SELL'">{{ o.side === 'BUY' ? 'COMPRA' : 'VENDA' }}</span> }
                  <span [class.down]="o.status === 'REFUSED'">{{ o.message }}</span>
                  @if (o.result_usd != null) { <b [class.up]="o.result_usd > 0" [class.down]="o.result_usd < 0"> US$ {{ o.result_usd | number: '1.2-2' }}</b> }
                </div>
              }
            </div>
          }
        </section>
      } @empty { <p class="muted">Nenhum evento de importância alta nas próximas 48 h.</p> }

      @if (past().length) {
        <section class="panel" style="margin-top:16px">
          <h3>Já saíram (últimas 6 h)</h3>
          <table class="small">
            <tr><th>Horário</th><th>Moeda</th><th>Evento</th><th class="right">Previsto</th><th class="right">Real</th><th>Planos</th></tr>
            @for (c of past(); track c.key) {
              @for (e of c.events; track e.code) {
                <tr>
                  <td class="mono">{{ c.at | date: 'HH:mm' : '-0300' }}</td><td>{{ c.currency }}</td><td>{{ e.name }}</td>
                  <td class="right mono">{{ e.forecast ?? '—' }}</td>
                  <td class="right mono" [class.up]="e.impact === 'POSITIVE'" [class.down]="e.impact === 'NEGATIVE'">{{ e.actual ?? 'sem resultado' }}</td>
                  <td class="small">@for (p of plansOf(c); track p.id) { {{ modeText(p.mode) }}: {{ statusText(p.status) }}; }</td>
                </tr>
              }
            }
          </table>
        </section>
      }
    } @else if (error()) {
      <p class="down">{{ error() }}</p>
    } @else { <p class="muted">Carregando…</p> }
  `,
  styles: `
    .head { display: flex; justify-content: space-between; align-items: center; gap: 12px; flex-wrap: wrap; }
    .evidence { display: grid; gap: 4px; margin-top: 8px; }
    .settings { display: flex; gap: 12px; align-items: flex-end; flex-wrap: wrap; margin-bottom: 12px; }
    .settings h3 { margin: 0 8px 4px 0; }
    .settings label { display: flex; flex-direction: column; gap: 2px; }
    input, select { font: inherit; padding: 3px 6px; border-radius: 6px; border: 1px solid var(--border); background: var(--panel-2); color: var(--text); }
    .settings input { width: 90px; }
    .ev { margin-bottom: 12px; }
    .ev.soon { border-color: var(--warn, #e0a800); }
    .evhead { display: flex; gap: 10px; align-items: center; flex-wrap: wrap; margin-bottom: 6px; }
    .when { font-size: 15px; }
    .badge.cur { font-weight: 700; }
    table.evs tr.main td { font-weight: 600; }
    .syms { display: flex; flex-wrap: wrap; gap: 6px; margin: 8px 0 4px; }
    .sym { display: inline-flex; align-items: center; gap: 4px; border: 1px solid var(--border); border-radius: 6px; padding: 2px 8px; cursor: pointer; }
    .sym.on { border-color: var(--up); }
    .sym input { width: auto; margin: 0; }
    .hist { margin: 2px 0 8px; }
    .actions { display: flex; gap: 8px; align-items: center; flex-wrap: wrap; }
    button.danger { border-color: var(--down); color: var(--down); }
    .plan { margin-top: 8px; padding: 6px 8px; border-left: 3px solid var(--border); }
    .po { margin-left: 8px; }
  `,
})
export class Principal implements OnInit, OnDestroy {
  private http = inject(HttpClient);
  protected data = signal<any>(null);
  protected error = signal<string | null>(null);
  protected now = signal<number>(Date.now());
  protected s = signal<Settings>(this.loadSettings());
  /** Símbolos marcados por evento (padrão: os pares de forex da moeda). */
  protected picks = signal<Record<string, string[]>>({});
  private timer?: ReturnType<typeof setInterval>;
  private clock?: ReturnType<typeof setInterval>;

  protected upcoming = computed<Cluster[]>(() => (this.data()?.clusters ?? [])
    .filter((c: Cluster) => new Date(c.at).getTime() > this.now() - 5 * 60_000));
  protected past = computed<Cluster[]>(() => (this.data()?.clusters ?? [])
    .filter((c: Cluster) => new Date(c.at).getTime() <= this.now() - 5 * 60_000).reverse());
  /** Algum evento de importância alta passou há mais de 10 min sem o valor real? */
  protected calendarStale = computed<string | null>(() => {
    const late = (this.data()?.clusters ?? []).filter((c: Cluster) => new Date(c.at).getTime() < this.now() - 10 * 60_000
      && c.events.some((e) => e.forecast != null) && c.events.every((e) => e.actual == null));
    return late.length ? late.map((c: Cluster) => `${c.currency} ${new Date(c.at).toISOString().substring(11, 16)} UTC`).join(', ') : null;
  });

  ngOnInit(): void {
    this.load();
    this.timer = setInterval(() => this.load(), 15_000);
    this.clock = setInterval(() => this.now.set(Date.now()), 10_000);
  }

  ngOnDestroy(): void {
    clearInterval(this.timer);
    clearInterval(this.clock);
  }

  private load(): void {
    this.http.get<any>('/api/desk?back=6&ahead=48').subscribe({
      next: (d) => { this.data.set(d); this.error.set(null); },
      error: () => this.error.set('API indisponível: reinicie o servidor com o jar novo.'),
    });
  }

  protected num(ev: Event): number | null {
    const v = (ev.target as HTMLInputElement).value;
    return v === '' ? null : +v;
  }

  private loadSettings(): Settings {
    try { return { ...DEFAULTS, ...JSON.parse(localStorage.getItem(KEY) ?? '{}') }; } catch { return DEFAULTS; }
  }

  protected patch(p: Partial<Settings>): void {
    this.s.set({ ...this.s(), ...p });
    try { localStorage.setItem(KEY, JSON.stringify(this.s())); } catch { /* só nesta aba */ }
  }

  protected minutesTo(c: Cluster): number {
    return Math.round((new Date(c.at).getTime() - this.now()) / 60_000);
  }

  protected countdown(c: Cluster): string {
    const m = this.minutesTo(c);
    if (m < 0) return `saiu há ${-m} min`;
    if (m < 60) return `em ${m} min`;
    const h = Math.floor(m / 60);
    return `em ${h} h ${String(m % 60).padStart(2, '0')}`;
  }

  // ------------------------------------------------------------------ símbolos
  protected pickedList(c: Cluster): string[] {
    return this.picks()[c.key] ?? c.symbols.filter((x) => x.market === 'fx' && x.dir !== 0).map((x) => x.symbol);
  }

  protected picked(c: Cluster, sym: string): boolean {
    return this.pickedList(c).includes(sym);
  }

  protected toggle(c: Cluster, sym: string): void {
    const cur = this.pickedList(c);
    this.picks.set({ ...this.picks(), [c.key]: cur.includes(sym) ? cur.filter((x) => x !== sym) : [...cur, sym] });
  }

  // ------------------------------------------------------------------ o que esperar
  protected consensusText(e: Ev, cur: string): string {
    if (e.consensus === 1) return `favorável (previsto ${e.forecast} ${e.forecast! > e.previous! ? '>' : '<'} anterior ${e.previous}; ${e.good_sign === 1 ? 'maior é bom' : 'maior é ruim'})`;
    if (e.consensus === -1) return `desfavorável (previsto ${e.forecast} ${e.forecast! > e.previous! ? '>' : '<'} anterior ${e.previous}; ${e.good_sign === 1 ? 'maior é bom' : 'maior é ruim'})`;
    if (e.consensus === 0) return 'previsto igual ao anterior: neutro';
    if (e.forecast == null) return 'sem previsto no calendário';
    return `sem histórico de qual lado é bom para ${cur}`;
  }

  protected consensusSide(c: Cluster): number | null {
    const main = c.events.find((e) => e.code === c.main);
    return main && (main.consensus === 1 || main.consensus === -1) ? main.consensus : null;
  }

  protected histTitle(x: Sym): string {
    if (x.abs15 == null) return 'Sem histórico deste evento neste símbolo (mapa de eventos)';
    const parts = [`nas últimas ${x.n} vezes andou em média ${x.abs15.toFixed(1)} ATR em 15 min`];
    if (x.upPos15 != null) parts.push(`real acima do previsto: subiu em ${(x.upPos15 * 100).toFixed(0)}% de ${x.nPos}`);
    if (x.upNeg15 != null) parts.push(`abaixo: subiu em ${(x.upNeg15 * 100).toFixed(0)}% de ${x.nNeg}`);
    if (x.spreadAtr != null) parts.push(`spread na hora = ${x.spreadAtr.toFixed(2)} ATR`);
    return parts.join(' · ');
  }

  protected historyLine(c: Cluster): string {
    const hs = c.symbols.filter((x) => x.abs15 != null);
    if (!hs.length) return 'Sem histórico deste evento no mapa de eventos (passe o mouse nos símbolos depois de rodar o mapa).';
    const top = [...hs].sort((a, b) => (b.abs15 ?? 0) - (a.abs15 ?? 0))[0];
    return `Histórico (passe o mouse nos símbolos): o que mais anda é ${top.symbol}, ${top.abs15!.toFixed(1)} ATR em 15 min em média (${top.n} vezes). `
      + 'O lado depende do número; nenhum símbolo mostrou direção confiável depois de entrar.';
  }

  // ------------------------------------------------------------------ planos
  protected plansOf(c: Cluster): any[] {
    return (this.data()?.plans ?? []).filter((p: any) => p.currency === c.currency && new Date(p.event_at).getTime() === new Date(c.at).getTime());
  }

  protected armedPlan(c: Cluster): any {
    return this.plansOf(c).find((p) => p.status === 'ARMED' && p.mode !== 'NOW') ?? null;
  }

  protected ordersOf(planId: number): any[] {
    return (this.data()?.orders ?? []).filter((o: any) => o.plan_id === planId);
  }

  protected modeText(m: string): string {
    return ({ MODEL: 'Automático pelo modelo', SURPRISE: 'Automático pelo dado', NOW: 'Agora (consenso)' } as Record<string, string>)[m] ?? m;
  }

  protected statusText(s: string): string {
    return ({ ARMED: 'armado', DONE: 'feito', EXPIRED: 'expirou', CANCELLED: 'cancelado', FAILED: 'falhou' } as Record<string, string>)[s] ?? s;
  }

  private confirmLive(text: string): boolean {
    if (this.data()?.mode === 'LIVE') return prompt(`${text}\n\nCONTA REAL: digite LIVE para confirmar.`) === 'LIVE';
    return confirm(text);
  }

  private body(c: Cluster, mode: string, currencySide: number | null): any {
    const s = this.s();
    return { currency: c.currency, at: c.at, eventCode: c.main, mode, symbols: this.pickedList(c), lot: s.lot,
      stopUsd: s.stopUsd, takeUsd: s.takeUsd, closeAfter: s.closeAfter, currencySide };
  }

  protected arm(c: Cluster): void {
    const s = this.s();
    const how = s.autoMode === 'MODEL' ? 'pelo modelo (previsão B 2 min depois; pula LATERAL)' : 'pelo dado (resultado × previsto)';
    const text = `Armar ${c.currency} das ${new Date(c.at).toISOString().substring(11, 16)} UTC ${how}?\n`
      + `Símbolos: ${this.pickedList(c).join(', ')}\nLote ${s.lot ?? 'mínimo'} · stop US$ ${s.stopUsd} · alvo US$ ${s.takeUsd}`
      + `${s.closeAfter ? ' · sai em ' + s.closeAfter + ' min' : ''}\nSem as travas de risco por ordem.`;
    if (!this.confirmLive(text)) return;
    this.http.post<any>('/api/desk/plan', this.body(c, s.autoMode, null)).subscribe({
      next: () => this.load(), error: (e) => alert(e?.error?.message ?? 'não consegui armar'),
    });
  }

  protected operateNow(c: Cluster, side: number): void {
    const s = this.s();
    const text = `Operar AGORA, antes do evento, ${side > 0 ? 'A FAVOR' : 'CONTRA'} ${c.currency}?\n`
      + `Símbolos: ${this.pickedList(c).join(', ')}\nLote ${s.lot ?? 'mínimo'} · stop US$ ${s.stopUsd} · alvo US$ ${s.takeUsd}`
      + `${s.closeAfter ? ' · sai em ' + s.closeAfter + ' min' : ''}\nNão testado; o spread alarga perto do evento. Sem as travas de risco por ordem.`;
    if (!this.confirmLive(text)) return;
    this.http.post<any>('/api/desk/plan', this.body(c, 'NOW', side)).subscribe({
      next: () => this.load(), error: (e) => alert(e?.error?.message ?? 'não consegui enviar'),
    });
  }

  protected cancel(id: number): void {
    this.http.post(`/api/desk/plan/${id}/cancel`, {}).subscribe(() => this.load());
  }
}
