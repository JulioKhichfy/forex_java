import { Component, OnDestroy, computed, effect, inject, signal } from '@angular/core';
import { DatePipe, DecimalPipe } from '@angular/common';
import { HttpClient } from '@angular/common/http';
import { FormsModule } from '@angular/forms';
import { Api, CalendarItem, JobStatus } from '../api';
import { MARKETS, MarketState } from '../market';
import { Tip } from './tip';
import { Brt } from '../brt';

/** Uma linha do mapa (GET /api/ops/events: o JSON do comando events-map). */
interface EvRow {
  eventCode: string; name: string | null; currency: string; importance: string; symbol: string; n: number;
  nPos: number; nNeg: number; abs15: number | null; abs60: number | null; upPos15: number | null; upNeg15: number | null;
  movPos15: number | null; movNeg15: number | null; upPos60: number | null; upNeg60: number | null;
  movPos60: number | null; movNeg60: number | null; spreadAtr: number | null; sameTime: number | null; last: string;
}
interface EvReport { market: string; builtAt: string; events: number; pairs: number; rows: EvRow[]; seconds: number }

@Component({
  selector: 'page-eventos',
  imports: [DatePipe, DecimalPipe, FormsModule, Tip, Brt],
  template: `
    <h1>Eventos · {{ marketLabel() }}</h1>
    <div class="notice">
      <b>O que cada divulgação costumou fazer com o preço</b> (desde 2022): quanto andou nos 15 e 60 min seguintes, em
      ATR, e <b>para que lado foi a partir de 2 min depois</b> (quando dá para entrar), separado por número
      <b>acima</b> ou <b>abaixo</b> do esperado. É descritivo, não regra validada: divulgações no mesmo horário dividem o
      mesmo movimento (CPI cheio, núcleo, mensal e anual saem juntos) e o passado não garante a próxima.
    </div>

    <div class="bar">
      <button (click)="run()" [disabled]="job()?.running">{{ job()?.running ? 'Atualizando…' : 'Atualizar o mapa deste mercado' }}</button>
      @if (report(); as r) {
        <span class="small muted">mapa de <span [brt]="r.builtAt">{{ r.builtAt | date: 'dd/MM HH:mm' : 'UTC' }} UTC</span> ·
          {{ r.events }} divulgações · {{ r.rows.length }} combinações evento × símbolo com 8+ casos</span>
      }
      @if (job()?.running) { <span class="small">{{ job()!.step }}</span> }
    </div>
    @if (missing()) { <p class="muted">Ainda não há mapa deste mercado: clique em <b>Atualizar o mapa deste mercado</b>.</p> }

    @if (report()) {
      <section class="panel">
        <h3>Próximos eventos (48 h) e o histórico deles <tip text="Os eventos de importância alta e moderada das moedas deste mercado, com o que a mesma divulgação fez antes em cada símbolo. Verde/vermelho = subiu/caiu em 65% ou mais das vezes naquele caso." /></h3>
        <div class="table-wrap"><table>
          <tr><th>Quando</th><th>Evento</th><th>Símbolo</th><th class="right">Casos</th><th class="right">Anda em 15 min</th>
            <th class="right">Se vier ACIMA do esperado</th><th class="right">Se vier ABAIXO do esperado</th><th class="right">Previsto</th></tr>
          @for (u of upcoming(); track u.key) {
            <tr>
              <td class="nowrap mono small" [brt]="u.ev.scheduled_at">{{ u.ev.scheduled_at | date: 'dd/MM HH:mm' : 'UTC' }}</td>
              <td>{{ u.ev.name }} <span class="badge">{{ u.ev.currency }}</span>
                @if (u.ev.importance === 'HIGH') { <span class="badge high">alta</span> }</td>
              @if (u.row; as r) {
                <td class="mono">{{ r.symbol }}</td><td class="right mono">{{ r.n }}</td>
                <td class="right mono">{{ r.abs15 | number: '1.1-1' }} ATR</td>
                <td class="right" [class.up]="(r.upPos15 ?? 0.5) >= 0.65" [class.down]="(r.upPos15 ?? 0.5) <= 0.35">{{ dirText(r.upPos15, r.nPos) }}</td>
                <td class="right" [class.up]="(r.upNeg15 ?? 0.5) >= 0.65" [class.down]="(r.upNeg15 ?? 0.5) <= 0.35">{{ dirText(r.upNeg15, r.nNeg) }}
                  @if (strength(r); as st) { <span class="badge" [class.ok]="st === 'forte'" [class.warn]="st === 'média'" [title]="strengthHelp">{{ st }}</span> }</td>
              } @else {
                <td colspan="5" class="muted small">sem histórico suficiente neste mercado</td>
              }
              <td class="right mono small">{{ u.ev.forecast ?? '—' }}</td>
            </tr>
          } @empty { <tr><td colspan="8" class="muted">Nenhum evento alto ou moderado das moedas deste mercado nas próximas 48 h.</td></tr> }
        </table></div>
      </section>

      <section class="panel" style="margin-top:16px">
        <h3>Mapa completo</h3>
        <div class="filters">
          <label class="small">Moeda <select [(ngModel)]="fCur"><option value="">todas</option>
            @for (c of currencies(); track c) { <option [value]="c">{{ c }}</option> }</select></label>
          <label class="small">Símbolo <select [(ngModel)]="fSym"><option value="">todos</option>
            @for (s of symbols(); track s) { <option [value]="s">{{ s }}</option> }</select></label>
          <label class="small">Casos mín. <input type="number" min="8" step="5" [(ngModel)]="fMin" /></label>
          <label class="small">Ordenar <select [(ngModel)]="fSort">
            <option value="move">pelo movimento em 15 min</option>
            <option value="dir">pela força da direção conforme a surpresa</option></select></label>
        </div>
        <div class="table-wrap"><table>
          <tr><th>Evento</th><th>Símbolo</th><th class="right">Casos</th>
            <th class="right">Anda 15 min <tip text="|preço 15 min depois − abertura do minuto da divulgação| ÷ ATR (média). Inclui o salto do primeiro minuto." /></th>
            <th class="right">Anda 60 min</th>
            <th class="right">Acima do esperado <tip text="Depois de entrar (+2 min) até +15 min: em que % das vezes subiu, quantos casos, e o movimento médio em ATR." /></th>
            <th class="right">Abaixo do esperado</th>
            <th class="right">Depende da surpresa <tip text="Diferença entre as duas colunas anteriores (pontos percentuais de 'subiu'). Grande = o lado depende do número; perto de zero = o evento mexe, mas não diz para onde. O selo diz se a diferença resiste ao acaso: com ~1.000 combinações, algumas parecem fortes por sorte." /></th>
            <th class="right">Spread <tip text="Custo de 1 spread na hora da divulgação, em ATR (mediana). Precisa ser bem menor que o movimento." /></th></tr>
          @for (r of shownRows(); track r.eventCode + r.symbol) {
            <tr>
              <td>{{ r.name ?? r.eventCode }} <span class="badge">{{ r.currency }}</span>
                @if ((r.sameTime ?? 0) >= 0.5) { <span class="small muted" title="Outras divulgações no mesmo minuto: o movimento é do conjunto">+{{ r.sameTime | number: '1.0-0' }} junto</span> }</td>
              <td class="mono">{{ r.symbol }}</td>
              <td class="right mono">{{ r.n }}</td>
              <td class="right mono">{{ r.abs15 | number: '1.1-1' }}</td>
              <td class="right mono">{{ r.abs60 | number: '1.1-1' }}</td>
              <td class="right small" [class.up]="(r.upPos15 ?? 0.5) >= 0.65" [class.down]="(r.upPos15 ?? 0.5) <= 0.35">{{ dirText(r.upPos15, r.nPos) }}{{ movText(r.movPos15) }}</td>
              <td class="right small" [class.up]="(r.upNeg15 ?? 0.5) >= 0.65" [class.down]="(r.upNeg15 ?? 0.5) <= 0.35">{{ dirText(r.upNeg15, r.nNeg) }}{{ movText(r.movNeg15) }}</td>
              <td class="right mono">{{ dep(r) === null ? '—' : (dep(r)! | number: '1.0-0') + ' p.p.' }}
                @if (strength(r); as st) { <span class="badge" [class.ok]="st === 'forte'" [class.warn]="st === 'média'" [title]="strengthHelp">{{ st }}</span> }</td>
              <td class="right mono small">{{ r.spreadAtr === null ? '—' : (r.spreadAtr | number: '1.2-2') }}</td>
            </tr>
          } @empty { <tr><td colspan="9" class="muted">Nada com estes filtros.</td></tr> }
        </table></div>
        @if (filtered().length > shownRows().length) { <p class="small muted">Mostrando {{ shownRows().length }} de {{ filtered().length }}.</p> }
      </section>
    }
  `,
  styles: `
    .bar { display: flex; gap: 10px; align-items: center; flex-wrap: wrap; margin: 12px 0; }
    .filters { display: flex; gap: 12px; flex-wrap: wrap; align-items: center; margin-bottom: 8px; }
    .filters input { width: 60px; }
    td.up { color: var(--up); font-weight: 600; }
    td.down { color: var(--down); font-weight: 600; }
  `,
})
export class Eventos implements OnDestroy {
  private api = inject(Api);
  private http = inject(HttpClient);
  private marketState = inject(MarketState);
  protected report = signal<EvReport | null>(null);
  protected missing = signal(false);
  protected cal = signal<CalendarItem[]>([]);
  protected job = signal<JobStatus | null>(null);
  protected fCur = '';
  protected fSym = '';
  protected fMin = 20;
  protected fSort: 'move' | 'dir' = 'move';
  private timer?: ReturnType<typeof setInterval>;
  private wasRunning = false;

  protected marketLabel = computed(() => MARKETS.find((m) => m.code === this.marketState.market())?.label ?? this.marketState.market());
  protected currencies = computed(() => [...new Set((this.report()?.rows ?? []).map((r) => r.currency))].sort());
  protected symbols = computed(() => [...new Set((this.report()?.rows ?? []).map((r) => r.symbol))].sort());

  constructor() {
    effect(() => { this.marketState.market(); this.load(); });
    this.timer = setInterval(() => this.pollJob(), 5_000);
    this.pollJob();
  }

  ngOnDestroy(): void {
    clearInterval(this.timer);
  }

  private load(): void {
    this.http.get<EvReport>(`/api/ops/events?market=${this.marketState.market()}`).subscribe({
      next: (r) => { this.report.set(r); this.missing.set(false); },
      error: () => { this.report.set(null); this.missing.set(true); },
    });
    this.api.upcoming(48).subscribe({ next: (c) => this.cal.set(c), error: () => this.cal.set([]) });
  }

  private pollJob(): void {
    this.api.jobs().subscribe({
      next: (j) => {
        this.job.set(j.current?.name === 'mapa-eventos' ? j.current : null);
        const running = !!j.current?.running;
        if (this.wasRunning && !running) this.load();
        this.wasRunning = running;
      },
      error: () => {},
    });
  }

  protected run(): void {
    this.http.post<JobStatus>(`/api/ops/jobs/mapa-eventos?market=${this.marketState.market()}`, {}).subscribe({
      next: (j) => { this.job.set(j); this.wasRunning = true; },
      error: (e) => alert(e?.error?.message ?? 'não consegui iniciar'),
    });
  }

  /** Próximos eventos das moedas que o mapa cobre, cada um com as linhas dos símbolos (a de mais casos primeiro). */
  protected upcoming = computed(() => {
    const rows = this.report()?.rows ?? [];
    const curs = new Set(rows.map((r) => r.currency));
    const out: { key: string; ev: CalendarItem; row: EvRow | null }[] = [];
    for (const ev of this.cal()) {
      if (!curs.has(ev.currency) || (ev.importance !== 'HIGH' && ev.importance !== 'MODERATE')) continue;
      const mine = rows.filter((r) => r.eventCode === ev.event_code).sort((a, b) => b.n - a.n).slice(0, 4);
      if (!mine.length) out.push({ key: `${ev.value_id}`, ev, row: null });
      mine.forEach((r) => out.push({ key: `${ev.value_id}-${r.symbol}`, ev, row: r }));
    }
    return out;
  });

  protected filtered = computed(() => this.report()?.rows ?? []);

  protected shownRows(): EvRow[] {
    let rows = (this.report()?.rows ?? []).filter((r) => r.n >= this.fMin
      && (!this.fCur || r.currency === this.fCur) && (!this.fSym || r.symbol === this.fSym));
    if (this.fSort === 'dir') rows = [...rows].sort((a, b) => (this.z(b) ?? -1) - (this.z(a) ?? -1));
    return rows.slice(0, 200);
  }

  protected strengthHelp = 'Teste de diferença de proporções (z). Forte: z ≥ 3,3 (acaso < 1 em 1.000, exigente porque o mapa tem ~1.000 combinações); média: z ≥ 2,6; sem selo: pode ser acaso.';

  /** Força estatística da diferença entre "subiu quando veio acima" e "subiu quando veio abaixo". */
  protected strength(r: EvRow): 'forte' | 'média' | null {
    const z = this.z(r);
    return z === null ? null : z >= 3.3 ? 'forte' : z >= 2.6 ? 'média' : null;
  }

  private z(r: EvRow): number | null {
    if (r.upPos15 === null || r.upNeg15 === null || r.nPos < 8 || r.nNeg < 8) return null;
    const p = (r.upPos15 * r.nPos + r.upNeg15 * r.nNeg) / (r.nPos + r.nNeg);
    const se = Math.sqrt(p * (1 - p) * (1 / r.nPos + 1 / r.nNeg));
    return se > 0 ? Math.abs(r.upPos15 - r.upNeg15) / se : null;
  }

  /** Quanto o lado depende da surpresa: |%subiu (acima) − %subiu (abaixo)|, em pontos. */
  protected dep(r: EvRow): number | null {
    if (r.upPos15 === null || r.upNeg15 === null || r.nPos < 5 || r.nNeg < 5) return null;
    return Math.abs(r.upPos15 - r.upNeg15) * 100;
  }

  protected dirText(up: number | null, n: number): string {
    if (up === null || n === 0) return '—';
    return `subiu ${(up * 100).toFixed(0)}% (${n})`;
  }

  protected movText(m: number | null): string {
    return m === null ? '' : ` · ${m >= 0 ? '+' : '−'}${Math.abs(m).toFixed(2)}`;
  }
}
