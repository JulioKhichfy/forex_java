import { Component, OnInit, effect, inject, signal } from '@angular/core';
import { DatePipe, DecimalPipe, KeyValuePipe, SlicePipe } from '@angular/common';
import { Api, HorizonSummary, MODEL_NAMES, ModelsInfo, Trades } from '../api';
import { JobsPanel } from './jobs-panel';
import { MarketState } from '../market';

@Component({
  selector: 'page-modelos',
  imports: [DatePipe, DecimalPipe, KeyValuePipe, SlicePipe, JobsPanel],
  template: `
    <h1>Modelos</h1>
    @if (info(); as i) {
      <section class="panel">
        <h3>Em produção</h3>
        @if (i.champion; as c) {
          <p>Versão <b class="mono">{{ c.version }}</b> desde {{ c.promotedAt | date: 'dd/MM/yyyy HH:mm' : 'UTC' }} UTC
            <span class="muted">· {{ c.note }}</span></p>
        } @else {
          <p class="muted">Nenhuma versão em produção. Rode <span class="mono">train-champion</span>.</p>
        }
        <div class="table-wrap"><table>
          <tr><th>Versão</th><th>Treinada em</th><th>Janela de treino</th><th>Contra a versão em uso</th><th></th></tr>
          @for (v of reversed(i); track v.version) {
            <tr>
              <td class="mono">{{ v.version }} @if (v.version === i.champion?.version) { <span class="badge ok">em uso</span> }</td>
              <td class="small">{{ v.trained_at | date: 'dd/MM/yyyy HH:mm' : 'UTC' }}</td>
              <td class="small">{{ v.train_from | slice: 0 : 10 }} a {{ v.train_to | slice: 0 : 10 }}</td>
              <td class="small">
                @if (v.challenge; as c) {
                  <span class="badge" [class.ok]="c.recommended" [class.warn]="!c.recommended">
                    {{ c.recommended ? 'recomendada' : 'não recomendada' }}</span> {{ c.reason }}
                  <details><summary class="muted">detalhes por modelo ({{ c.month }})</summary>
                    <table>
                      <tr><th>Modelo</th><th>h</th><th class="right">Log loss em uso</th><th class="right">Nova</th>
                        <th>Operações em uso</th><th>Nova</th></tr>
                      @for (r of c.rows; track r.model + r.horizon) {
                        <tr>
                          <td>{{ names[r.model] ?? r.model }}</td><td>{{ r.horizon }}</td>
                          <td class="right mono">{{ r.llChampion | number: '1.4-4' }}</td>
                          <td class="right mono" [class.up]="r.llChallenger < r.llChampion">{{ r.llChallenger | number: '1.4-4' }}</td>
                          <td class="small">{{ trades(r.tradesChampion ?? undefined) }}</td>
                          <td class="small">{{ trades(r.tradesChallenger ?? undefined) }}</td>
                        </tr>
                      }
                    </table>
                  </details>
                } @else if (v.version !== i.champion?.version) {
                  <span class="muted">sem comparação</span>
                }
              </td>
              <td>
                @if (v.version !== i.champion?.version) {
                  <button (click)="promote(v.version)">Promover</button>
                }
              </td>
            </tr>
          }
        </table></div>
      </section>

      <section class="panel" style="margin-top:16px">
        <h3>Treinamento</h3>
        <p class="muted small">Uma vez por mês: normaliza os dados novos, recalcula as features e treina uma versão nova
          com os últimos 24 meses. Ela é comparada à versão em uso no último mês completo e fica esperando a sua decisão
          (botão Promover acima). Leva de 30 a 50 minutos; o servidor continua funcionando.</p>
        <jobs-panel [names]="['treinamento-mensal']" highlight="treinamento-mensal" (finished)="reload()" />
      </section>

      @if (i.lockbox_summary; as lb) {
        <section class="panel" style="margin-top:16px">
          <h3>Cofre — avaliação única e honesta ({{ i.lockbox?.run }})</h3>
          <p class="muted small">Os meses do cofre nunca entraram em treino nem em comparação antes desta avaliação.
            É a melhor estimativa do que esperar ao vivo.</p>
          @for (h of lb; track h.horizon) {
            <div class="summary">
              <h2>{{ h.horizon }} min · {{ h.first_test }} a {{ h.last_test }} · {{ h.rows }} momentos</h2>
              <div class="table-wrap"><table>
                <tr><th>Modelo</th><th class="right">Log loss (base {{ h.ll_base | number: '1.4-4' }})</th>
                  <th class="right">AUC alta</th><th>Operações gate 4</th><th>Com limiar 0,45 / 0,15</th></tr>
                @for (m of h.models | keyvalue; track m.key) {
                  <tr>
                    <td>{{ names[m.key] ?? m.key }}</td>
                    <td class="right mono">{{ m.value.ll | number: '1.4-4' }}</td>
                    <td class="right mono">{{ m.value.aucUp | number: '1.3-3' }}</td>
                    <td class="small">{{ trades(m.value.trades) }}</td>
                    <td class="small">{{ trades(loose(h, m.key)) }}</td>
                  </tr>
                }
              </table></div>
            </div>
          }
        </section>
      }

      @if (i.walkforward_summary; as wf) {
        <section class="panel" style="margin-top:16px">
          <h3>Walk-forward de referência ({{ i.walkforward_run }})</h3>
          @for (h of wf; track h.horizon) {
            <div class="summary">
              <h2>{{ h.horizon }} min · {{ h.folds }} meses ({{ h.first_test }} a {{ h.last_test }})</h2>
              <div class="table-wrap"><table>
                <tr><th>Modelo</th><th class="right">Log loss (base {{ h.ll_base | number: '1.4-4' }})</th>
                  <th class="right">AUC alta</th><th>Operações gate 4</th><th>Com limiar 0,45 / 0,15</th></tr>
                @for (m of h.models | keyvalue; track m.key) {
                  <tr>
                    <td>{{ names[m.key] ?? m.key }}</td>
                    <td class="right mono">{{ m.value.ll | number: '1.4-4' }}</td>
                    <td class="right mono">{{ m.value.aucUp | number: '1.3-3' }}</td>
                    <td class="small">{{ trades(m.value.trades) }}</td>
                    <td class="small">{{ trades(loose(h, m.key)) }}</td>
                  </tr>
                }
              </table></div>
            </div>
          }
        </section>
      }
    } @else {
      <p class="muted">Carregando…</p>
    }
  `,
  styles: `.summary + .summary { margin-top: 16px; } h2 { font-size: 14px; }`,
})
export class Modelos implements OnInit {
  private api = inject(Api);
  private marketState = inject(MarketState);
  protected names = MODEL_NAMES;
  protected info = signal<ModelsInfo | null>(null);

  constructor() {
    effect(() => { this.marketState.market(); this.reload(); });
  }

  ngOnInit(): void {
  }

  protected reload(): void {
    this.api.models(this.marketState.market()).subscribe((i) => this.info.set(i));
  }

  protected reversed(i: ModelsInfo) {
    return [...i.versions].reverse();
  }

  protected promote(version: string): void {
    if (!confirm(`Colocar a versão ${version} em produção? As próximas previsões usam ela (dá para voltar).`)) return;
    this.api.promote(version, this.marketState.market()).subscribe(() => this.reload());
  }

  protected loose(h: HorizonSummary, model: string): Trades | undefined {
    return h.sweep?.find((s) => s.minProb === 0.45)?.models?.[model];
  }

  protected trades(t: Trades | undefined): string {
    if (!t || !t.n) return 'nenhuma';
    const pf = t.profitFactor == null || isNaN(t.profitFactor) ? '—' : t.profitFactor.toFixed(2);
    return `${t.n} · E[R] ${t.expectancyR.toFixed(2)} · PF ${pf} · acerto ${(100 * t.hitRate).toFixed(0)}%`;
  }
}
