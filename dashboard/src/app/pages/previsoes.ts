import { Component, OnDestroy, OnInit, computed, effect, inject, signal } from '@angular/core';
import { DatePipe, DecimalPipe, SlicePipe } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { Api, LatestPredictions, MODEL_NAMES, MODEL_ORDER, Prediction, RunResult, Score } from '../api';
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
          @if (tally(); as t) {
            <span class="small">😊 <b class="up">{{ t.hits }}</b> acerto(s) · 😞 <b class="down">{{ t.misses }}</b> erro(s)
              @if (t.hits + t.misses) { · {{ 100 * t.hits / (t.hits + t.misses) | number: '1.0-0' }}% }
              @if (!withFlat() && t.flat) { <span class="muted"> · {{ t.flat }} LATERAL fora da conta</span> }
              @if (t.pending) { <span class="muted"> · {{ t.pending }} aguardando</span> }</span>
          }
        </div>
        <div class="table-wrap"><table>
          <tr><th>Momento</th><th>Modelo</th><th>h <tip key="horizonte" /></th><th>Probabilidades <tip key="probabilidades" /></th><th>Sinal <tip key="gate4" /></th><th>Resultado <tip text="O que aconteceu de fato: ALTA ou QUEDA se andou mais de meio ATR já pagando o spread; senão LATERAL." /></th>
            <th>Acertou? <tip text="😊 a previsão bateu com o que aconteceu; 😞 errou. Sem a opção LATERAL, vale a direção mais provável entre alta e queda, e os momentos LATERAL ficam sem marca." /></th></tr>
          @for (p of history(); track p.id) {
            <tr>
              <td class="small mono nowrap" [brt]="p.momentUtc">{{ p.momentUtc | date: 'dd/MM HH:mm' : 'UTC' }}</td>
              <td class="small">{{ names[p.model] ?? p.model }}</td>
              <td class="small">{{ p.horizonMin }}</td>
              <td><prob-bar [down]="p.pDown" [flat]="p.pFlat" [up]="p.pUp" /></td>
              <td class="small">{{ p.signal === 'NO_TRADE' ? '—' : p.signal === 'BUY' ? 'COMPRA' : 'VENDA' }}</td>
              <td class="small">{{ p.label ?? 'aguardando' }}</td>
              <td class="emoji" [title]="'previsto: ' + (guess(p) ?? '—')">{{ verdict(p) === true ? '😊' : verdict(p) === false ? '😞' : '' }}</td>
            </tr>
          } @empty {
            <tr><td colspan="7" class="muted">{{ selected() ? 'Sem histórico.' : 'Selecione um par na tabela.' }}</td></tr>
          }
        </table></div>
      </section>
    </div>
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
    td.emoji { font-size: 18px; text-align: center; }
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
  protected tally = computed(() => {
    const t = { hits: 0, misses: 0, flat: 0, pending: 0 };
    this.withFlat();
    for (const p of this.history()) {
      if (!p.label) { t.pending++; continue; }
      const v = this.verdict(p);
      if (v === true) t.hits++;
      else if (v === false) t.misses++;
      else t.flat++;
    }
    return this.history().length ? t : null;
  });

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
