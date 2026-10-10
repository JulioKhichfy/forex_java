import { Component, OnDestroy, OnInit, computed, inject, signal } from '@angular/core';
import { DatePipe, DecimalPipe, SlicePipe } from '@angular/common';
import { Api, LatestPredictions, MODEL_NAMES, MODEL_ORDER, Prediction, RunResult, Score } from '../api';
import { ProbBar } from './prob-bar';

@Component({
  selector: 'page-previsoes',
  imports: [DatePipe, DecimalPipe, SlicePipe, ProbBar],
  template: `
    <div class="head">
      <h1>Previsões ao vivo</h1>
      <button class="primary" (click)="runNow()" [disabled]="running()">{{ running() ? 'Prevendo…' : 'Prever agora' }}</button>
    </div>
    @if (run(); as r) {
      <p class="small" [class.muted]="!r.note">
        @if (r.note) { <span class="badge warn">{{ r.note }}</span> }
        @else { {{ r.rows }} previsões às {{ r.at | date: 'HH:mm' : 'UTC' }} UTC para {{ r.symbols.length }} pares. }
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
          @for (m of models; track m) { <th colspan="2" class="group">{{ names[m] }}</th> }
        </tr>
        <tr>
          @for (m of models; track m) { <th>15 min</th><th>60 min</th> }
        </tr>
        @for (s of symbols(); track s) {
          <tr (click)="select(s)" class="row" [class.sel]="s === selected()">
            <td class="mono">{{ s }}</td>
            <td class="small muted nowrap">{{ moment(s) | date: 'dd/MM HH:mm' : 'UTC' }}</td>
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
        <h3>Placar ao vivo (últimos 90 dias)</h3>
        <div class="table-wrap"><table>
          <tr><th>Modelo</th><th>Horizonte</th><th class="right">Resolvidas</th><th class="right">Acerto da classe</th>
            <th class="right">Sinais</th><th class="right">E[R]</th><th class="right">Acerto</th></tr>
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
          operação com stop 1,5 ATR e alvo 1,5 × stop, já com o spread. Previsões manuais ficam fora.</p>
      </section>

      <section class="panel">
        <h3>Histórico {{ selected() ?? '' }} (48 h)</h3>
        <div class="table-wrap"><table>
          <tr><th>Momento</th><th>Modelo</th><th>h</th><th>Probabilidades</th><th>Sinal</th><th>Resultado</th></tr>
          @for (p of history(); track p.id) {
            <tr>
              <td class="small mono nowrap">{{ p.momentUtc | date: 'dd/MM HH:mm' : 'UTC' }}</td>
              <td class="small">{{ names[p.model] ?? p.model }}</td>
              <td class="small">{{ p.horizonMin }}</td>
              <td><prob-bar [down]="p.pDown" [flat]="p.pFlat" [up]="p.pUp" /></td>
              <td class="small">{{ p.signal === 'NO_TRADE' ? '—' : p.signal === 'BUY' ? 'COMPRA' : 'VENDA' }}</td>
              <td class="small">{{ p.label ?? 'aguardando' }}</td>
            </tr>
          } @empty {
            <tr><td colspan="6" class="muted">{{ selected() ? 'Sem histórico.' : 'Selecione um par na tabela.' }}</td></tr>
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
  `,
})
export class Previsoes implements OnInit, OnDestroy {
  private api = inject(Api);
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

  ngOnInit(): void {
    this.load();
    this.timer = setInterval(() => this.load(), 60_000);
  }

  ngOnDestroy(): void {
    clearInterval(this.timer);
  }

  protected cell(symbol: string, model: string, h: number): Prediction | undefined {
    return this.byKey().get(`${symbol}|${model}|${h}`);
  }

  protected moment(symbol: string): string | undefined {
    return (this.latest()?.predictions ?? []).find((p) => p.symbol === symbol)?.momentUtc;
  }

  protected select(symbol: string): void {
    this.selected.set(symbol);
    this.api.history(symbol, 48).subscribe((h) => this.history.set(h));
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
    this.api.latest().subscribe({ next: (l) => this.latest.set(l), error: () => this.latest.set(null) });
    this.api.scoreboard(90).subscribe({ next: (s) => this.score.set(s.models), error: () => this.score.set([]) });
    const s = this.selected();
    if (s) this.api.history(s, 48).subscribe((h) => this.history.set(h));
  }
}
