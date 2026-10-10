import { Component, OnInit, inject, signal } from '@angular/core';
import { DatePipe, DecimalPipe, KeyValuePipe, SlicePipe } from '@angular/common';
import { Api, HorizonSummary, MODEL_NAMES, ModelsInfo, Trades } from '../api';

@Component({
  selector: 'page-modelos',
  imports: [DatePipe, DecimalPipe, KeyValuePipe, SlicePipe],
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
          <tr><th>Versão</th><th>Treinada em</th><th>Janela de treino</th><th>Modelos</th></tr>
          @for (v of i.versions; track v.version) {
            <tr>
              <td class="mono">{{ v.version }} @if (v.version === i.champion?.version) { <span class="badge ok">em uso</span> }</td>
              <td class="small">{{ v.trained_at | date: 'dd/MM/yyyy HH:mm' : 'UTC' }}</td>
              <td class="small">{{ v.train_from | slice: 0 : 10 }} a {{ v.train_to | slice: 0 : 10 }}</td>
              <td class="small">@for (e of v.entries; track e.model + e.horizon) { {{ names[e.model] ?? e.model }} {{ e.horizon }}m · }</td>
            </tr>
          }
        </table></div>
        <p class="muted small">O botão de treinamento mensal e a promoção pelo dashboard chegam na etapa 6d.</p>
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
  protected names = MODEL_NAMES;
  protected info = signal<ModelsInfo | null>(null);

  ngOnInit(): void {
    this.api.models().subscribe((i) => this.info.set(i));
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
