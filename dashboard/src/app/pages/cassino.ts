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
interface Row { symbol: string; ready: boolean; predicted: boolean }
interface Group { code: string; label: string | null; market: string; optional: boolean; symbols: Row[] }

const DEFAULT: Form = { side: 'BUY', auto: false, lot: null, takeUsd: 0.10, stopUsd: 1.00, times: 10, attempts: 1, closeAfter: null };
const GROUPS_KEY = 'jev.cassino.grupos';

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
      bateu o alvo, a vez termina e começa a próxima. Total de ordens ≤ vezes × tentativas. Valem só as travas técnicas (EA,
      cotação, lote, mercado aberto, BLOCK, alvo e stop além de 2 spreads) e a <b>perda do dia/semana</b>, que para tudo. As
      posições do Cassino ficam fora do trailing e da prorrogação.
      <br><b>Repetir até acertar não cria vantagem</b>: com alvo pequeno e stop maior, a maioria das tentativas acerta, mas
      cada stop apaga vários alvos. Cada operação ainda paga o spread — nos exóticos, bem mais. O backtest mostra só a última
      hora; é pouco para dizer se a configuração presta.
      <br><b>Deixa comigo!</b>: o lado de cada ordem é o mais provável (alta × queda) na previsão mais recente do modelo B do
      símbolo, 15 min antes de 60 min, de até 75 min atrás; sem previsão recente, a linha espera. Só nos símbolos com modelo
      (não nos extras). No Laboratório, entrar sempre no lado mais provável deu perto de zero ou negativo na maioria dos
      mercados: é a melhor opinião que eu tenho, não uma vantagem.
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
          com rodadas sobrando, recomeça as linhas ligadas com as vezes e tentativas zeradas. Se as linhas terminarem sozinhas, o Cassino para.</p>
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
          <div class="actions">
            @for (gr of optionalGroups(); track gr.code) {
              <label class="small incl"><input type="checkbox" [checked]="included().includes(gr.code)" (change)="toggleGroup(gr.code)" />
                incluir {{ gr.label }} ({{ gr.symbols.length }})</label>
            }
            <button [disabled]="!dirtyCount()" [class.primary]="dirtyCount() > 0" (click)="saveAll()">
              Salvar todos @if (dirtyCount()) { ({{ dirtyCount() }}) }</button>
            <button (click)="backtestAll()">Backtest de todos</button>
          </div>
        </div>
        <div class="table-wrap">
          <table class="small cas">
            <colgroup>
              <col class="c-sym" /><col class="c-num" /><col class="c-num" /><col class="c-num" /><col class="c-dir" />
              <col class="c-int" /><col class="c-int" /><col class="c-int" /><col class="c-act" /><col class="c-state" />
            </colgroup>
            <thead>
              <tr><th>Símbolo</th><th>Alvo US$</th><th>Stop US$</th><th>Lote</th><th>Direção</th>
                <th title="Quantas vezes abrir, aconteça o que acontecer">Vezes</th>
                <th title="Em cada vez: até quantas ordens para bater o alvo">Tentativas</th>
                <th title="Opcional: fecha a mercado depois desses minutos (vazio = só alvo e stop)">Saída min</th>
                <th></th><th>Estado</th></tr>
            </thead>
            @for (gr of visibleGroups(); track gr.code) {
              <tbody>
                @if (gr.label) { <tr class="group"><td colspan="10">{{ gr.label }}</td></tr> }
                @for (r of gr.symbols; track r.symbol) {
                  @let sym = r.symbol;
                  @let l = s.lines[sym];
                  @let f = form(sym);
                  @let running = l?.status === 'RUNNING';
                  @let off = running || !r.ready;
                  <tr class="row" [class.on]="running" [class.na]="!r.ready">
                    <td class="mono sym"><b>{{ sym }}</b>
                      @if (dirty(sym)) { <span class="dot" title="Alterado e não salvo">●</span> }</td>
                    <td><input type="number" min="0.01" step="0.05" [value]="f.takeUsd" [disabled]="off" (input)="patch(sym, { takeUsd: num($event) ?? 0 })" /></td>
                    <td><input type="number" min="0.01" step="0.10" [value]="f.stopUsd" [disabled]="off" (input)="patch(sym, { stopUsd: num($event) ?? 0 })" /></td>
                    <td><input type="number" min="0.01" step="0.01" [value]="f.lot ?? ''" placeholder="mínimo" [disabled]="off" (input)="patch(sym, { lot: num($event) })" /></td>
                    <td>
                      <div class="dir">
                        <select [value]="f.side" [disabled]="off || f.auto" (change)="patch(sym, { side: $any($event.target).value })">
                          <option value="BUY">Compra</option><option value="SELL">Venda</option></select>
                        <label class="auto" [title]="r.predicted ? 'O sistema decide o lado de cada ordem pela previsão mais recente do modelo B' : 'Sem previsão do modelo para este símbolo'">
                          <input type="checkbox" [checked]="f.auto && r.predicted" [disabled]="off || !r.predicted" (change)="patch(sym, { auto: $any($event.target).checked })" />
                          Deixa comigo!</label>
                      </div>
                    </td>
                    <td><input type="number" min="1" step="1" [value]="f.times" [disabled]="off" (input)="patch(sym, { times: num($event) ?? 1 })" /></td>
                    <td><input type="number" min="1" step="1" [value]="f.attempts" [disabled]="off" (input)="patch(sym, { attempts: num($event) ?? 1 })" /></td>
                    <td><input type="number" min="1" step="1" [value]="f.closeAfter ?? ''" placeholder="—" [disabled]="off" (input)="patch(sym, { closeAfter: num($event) })" /></td>
                    <td>
                      <div class="btns">
                        @if (running) { <button class="danger" (click)="stop(sym)">Parar</button> }
                        @else { <button class="primary" [disabled]="s.closing || !r.ready" (click)="start(sym, r)">Ligar</button> }
                        <button [disabled]="!dirty(sym)" (click)="save([sym])" title="Salvar a configuração deste par">Salvar</button>
                        <button [disabled]="!r.ready" (click)="backtest(sym, r)" title="Últimos 4 quartos de hora">4 quartos</button>
                      </div>
                    </td>
                    <td class="state">
                      @if (!r.ready) { <span class="muted" title="Atualize o JevCandleExporter (1.1) e o JevExecutor (2.9)">sem dados do MT5</span> }
                      @else if (l && l.status !== 'OFF') {
                        <span class="badge" [class.ok]="l.status === 'RUNNING' || l.status === 'DONE'" [class.high]="l.status === 'STOPPED' || l.status === 'EXHAUSTED'">{{ statusText(l.status) }}</span>
                        <span class="muted"> {{ l.cycle || 1 }}/{{ l.times || 1 }} · {{ l.used }}/{{ l.attempts }} · {{ l.hits || 0 }} alvo(s)</span>
                      } @else if (saved(sym)) { <span class="muted">configuração salva</span> }
                    </td>
                  </tr>
                  @if ((l?.message && l?.status !== 'OFF') || bt()[sym]) {
                    <tr class="detail" [class.on]="running">
                      <td></td>
                      <td colspan="9">
                        @if (l?.message && l?.status !== 'OFF') { <div class="muted">{{ l!.message }}</div> }
                        @if (bt()[sym]; as b) {
                          <div class="quarters">
                            <span class="muted">backtest:</span>
                            @if (b.message) { <span class="muted">{{ b.message }}</span> }
                            @for (q of b.quarters; track q.start) {
                              <span class="badge" [class.ok]="q.result === 'ALVO'" [class.high]="q.result === 'STOP'"
                                [title]="(q.minutes ?? '—') + ' min'"><span [brt]="q.start">{{ q.start | date: 'HH:mm' : 'UTC' }}</span>
                                @if (f.auto && q.side) { {{ q.side === 'BUY' ? 'C' : 'V' }} }
                                {{ resultText(q.result) }} @if (q.usd !== null) { {{ q.usd | number: '1.2-2' }} }</span>
                            }
                            @if (b.spread_usd !== undefined) {
                              <span class="muted">lote {{ b.lot }} · spread {{ b.spread_points }} pts = US$ {{ b.spread_usd | number: '1.2-2' }}</span>
                              @if (b.too_close) { <b class="down">alvo ou stop dentro de 2 spreads: a ordem é recusada</b> }
                            }
                          </div>
                        }
                      </td>
                    </tr>
                  }
                }
              </tbody>
            } @empty { <tbody><tr><td colspan="10" class="muted">Nenhum símbolo deste mercado.</td></tr></tbody> }
          </table>
        </div>
        <p class="small muted">Salvar guarda a configuração do par no servidor (vale em qualquer navegador); ligar também salva.
          ● = alterado e não salvo. Backtest: se a linha tivesse aberto no início de cada um dos últimos 4 quartos de hora
          completos (UTC), com o spread de agora — alvo, stop, tempo ou ainda aberta. Alvo e stop no mesmo minuto contam como
          stop. Com <b>Deixa comigo!</b>, cada quarto usa o lado que a previsão daquela hora daria (C = compra, V = venda).</p>
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
    .actions { display: flex; gap: 8px; flex-wrap: wrap; align-items: center; }
    button.danger { border-color: var(--down); color: var(--down); }
    button.big { font-weight: 700; padding: 8px 14px; }
    .global { display: flex; gap: 10px; align-items: flex-end; flex-wrap: wrap; }
    .global label { display: flex; flex-direction: column; gap: 2px; }
    input, select { font: inherit; padding: 3px 6px; border-radius: 6px; border: 1px solid var(--border); background: var(--panel-2); color: var(--text); }
    label.incl { display: inline-flex; align-items: center; gap: 4px; white-space: nowrap; }

    table.cas { table-layout: fixed; width: 100%; min-width: 1040px; border-collapse: collapse; }
    table.cas col.c-sym { width: 96px; }
    table.cas col.c-num { width: 84px; }
    table.cas col.c-dir { width: 210px; }
    table.cas col.c-int { width: 72px; }
    table.cas col.c-act { width: 210px; }
    table.cas col.c-state { width: auto; }
    table.cas th { text-align: left; white-space: nowrap; padding: 6px 6px; vertical-align: bottom; }
    table.cas td { padding: 4px 6px; vertical-align: middle; }
    table.cas tr.row td { height: 34px; border-top: 1px solid var(--border); }
    table.cas input, table.cas select { width: 100%; box-sizing: border-box; height: 26px; padding: 2px 6px; }
    table.cas input[type='checkbox'] { width: auto; height: auto; margin: 0; }
    table.cas td.sym { white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
    table.cas .dot { color: var(--accent, #e0a800); margin-left: 4px; font-size: 10px; }
    .dir { display: flex; align-items: center; gap: 6px; }
    .dir select { flex: 0 0 92px; width: 92px; }
    label.auto { display: inline-flex; align-items: center; gap: 4px; white-space: nowrap; }
    .btns { display: flex; gap: 4px; flex-wrap: nowrap; }
    .btns button { padding: 3px 8px; white-space: nowrap; }
    td.state { white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
    tr.group td { padding-top: 12px; font-weight: 600; color: var(--muted); text-transform: uppercase; font-size: 11px; letter-spacing: .04em; }
    tr.on td { background: color-mix(in srgb, var(--up) 8%, transparent); }
    tr.na td { opacity: .55; }
    tr.detail td { padding: 0 6px 6px; font-size: 12px; }
    .quarters { display: flex; gap: 6px; flex-wrap: wrap; align-items: center; }
  `,
})
export class Cassino implements OnInit, OnDestroy {
  private http = inject(HttpClient);
  private marketState = inject(MarketState);
  protected view = signal<any>(null);
  protected error = signal<string | null>(null);
  /** Alterações ainda não salvas, por símbolo. */
  protected drafts = signal<Record<string, Form>>({});
  protected bt = signal<Record<string, Backtest>>({});
  protected globalForm = signal<{ takeUsd: number | null; lossUsd: number | null; rounds: number } | null>(null);
  protected included = signal<string[]>(this.loadIncluded());
  private timer?: ReturnType<typeof setInterval>;

  protected marketLabel = computed(() => MARKETS.find((m) => m.code === this.marketState.market())?.label ?? this.marketState.market());
  private marketGroups = computed<Group[]>(() => (this.view()?.groups ?? []).filter((gr: Group) => gr.market === this.marketState.market()));
  protected optionalGroups = computed<Group[]>(() => this.marketGroups().filter((gr) => gr.optional));
  protected visibleGroups = computed<Group[]>(() => this.marketGroups()
    .filter((gr) => gr.symbols.length && (!gr.optional || this.included().includes(gr.code))));
  protected otherRunning = computed<Line[]>(() => Object.values<Line>(this.view()?.state?.lines ?? {})
    .filter((l) => l.status === 'RUNNING' && l.market !== this.marketState.market()));
  protected dirtyCount = computed(() => Object.keys(this.drafts()).length);
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

  /** O que está valendo: a linha ligada; senão a alteração não salva; senão a configuração salva; senão o padrão. */
  protected form(sym: string): Form {
    const l: Line | undefined = this.view()?.state?.lines?.[sym];
    if (l?.status === 'RUNNING') {
      return { side: l.auto ? 'BUY' : l.side, auto: !!l.auto, lot: l.lot, takeUsd: l.takeUsd, stopUsd: l.stopUsd,
        times: l.times || 1, attempts: l.attempts, closeAfter: l.closeAfterMinutes };
    }
    return this.drafts()[sym] ?? this.saved(sym) ?? DEFAULT;
  }

  protected saved(sym: string): Form | null {
    const c = this.view()?.configs?.[sym];
    return c ? { ...DEFAULT, ...c } : null;
  }

  protected dirty(sym: string): boolean {
    return !!this.drafts()[sym];
  }

  protected patch(sym: string, p: Partial<Form>): void {
    this.drafts.set({ ...this.drafts(), [sym]: { ...this.form(sym), ...p } });
  }

  protected save(symbols: string[]): void {
    const body: Record<string, Form> = {};
    for (const s of symbols) body[s] = this.form(s);
    this.http.post<any>('/api/casino/configs', body).subscribe({
      next: (all) => {
        const d = { ...this.drafts() };
        for (const s of symbols) delete d[s];
        this.drafts.set(d);
        const v = this.view();
        if (v) this.view.set({ ...v, configs: all });
      },
      error: (e) => alert(e?.error?.message ?? 'não consegui salvar'),
    });
  }

  protected saveAll(): void {
    const syms = Object.keys(this.drafts());
    if (syms.length) this.save(syms);
  }

  private loadIncluded(): string[] {
    try { return JSON.parse(localStorage.getItem(GROUPS_KEY) ?? '[]'); } catch { return []; }
  }

  protected toggleGroup(code: string): void {
    const cur = this.included();
    const next = cur.includes(code) ? cur.filter((c) => c !== code) : [...cur, code];
    this.included.set(next);
    try { localStorage.setItem(GROUPS_KEY, JSON.stringify(next)); } catch { /* só nesta aba */ }
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

  protected start(sym: string, r: Row): void {
    const f = this.form(sym);
    const auto = f.auto && r.predicted;
    const side = auto ? 'lado pela previsão (Deixa comigo!)' : f.side === 'BUY' ? 'COMPRA' : 'VENDA';
    const text = `Ligar ${sym}: ${side}, lote ${f.lot ?? 'mínimo'}, alvo US$ ${f.takeUsd}, stop US$ ${f.stopUsd}, `
      + `${f.times} vez(es) × até ${f.attempts} tentativa(s)${f.closeAfter ? `, sai em ${f.closeAfter} min` : ''}?\n`
      + `Até ${f.times * f.attempts} ordens. A primeira sai AGORA, sem as travas de risco por ordem (valem as técnicas e a perda do dia/semana).`;
    if (this.view()?.mode === 'LIVE') {
      if (prompt(`${text}\n\nCONTA REAL: digite LIVE para confirmar.`) !== 'LIVE') return;
    } else if (!confirm(text)) return;
    const q = new URLSearchParams({ symbol: sym, side: f.side, auto: String(auto), takeUsd: String(f.takeUsd),
      stopUsd: String(f.stopUsd), times: String(f.times || 1), attempts: String(f.attempts || 1) });
    if (f.lot) q.set('lot', String(f.lot));
    if (f.closeAfter) q.set('closeAfter', String(f.closeAfter));
    this.http.post<any>(`/api/casino/start?${q}`, {}).subscribe({
      next: () => {   // ligar também salva a configuração no servidor
        const d = { ...this.drafts() };
        delete d[sym];
        this.drafts.set(d);
        this.load();
      },
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

  protected backtest(sym: string, r: Row): void {
    const f = this.form(sym);
    const q = new URLSearchParams({ symbol: sym, side: f.side, auto: String(f.auto && r.predicted),
      takeUsd: String(f.takeUsd), stopUsd: String(f.stopUsd) });
    if (f.lot) q.set('lot', String(f.lot));
    if (f.closeAfter) q.set('closeAfter', String(f.closeAfter));
    this.http.get<Backtest>(`/api/casino/backtest?${q}`).subscribe({
      next: (b) => this.bt.set({ ...this.bt(), [sym]: b }),
      error: (e) => this.bt.set({ ...this.bt(), [sym]: { symbol: sym, lot: 0, quarters: [], message: e?.error?.message ?? 'backtest indisponível' } }),
    });
  }

  protected backtestAll(): void {
    for (const gr of this.visibleGroups()) for (const r of gr.symbols) if (r.ready) this.backtest(r.symbol, r);
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
