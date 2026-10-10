import { Component, OnDestroy, OnInit, computed, inject, signal } from '@angular/core';
import { DatePipe, DecimalPipe } from '@angular/common';
import { RouterLink } from '@angular/router';
import { Api, CalendarItem, LatestPredictions, MODEL_NAMES, NewsItem } from '../api';
import { ProbBar } from './prob-bar';

@Component({
  selector: 'page-hoje',
  imports: [DatePipe, DecimalPipe, RouterLink, ProbBar],
  template: `
    <div class="notice">
      <b>Apoio à decisão, não sinal garantido.</b> No teste fora da amostra nenhum modelo superou o custo de forma
      consistente. Confira o <a routerLink="/previsoes">placar ao vivo</a> e o resultado do cofre em
      <a routerLink="/modelos">Modelos</a> antes de confiar numa previsão.
    </div>

    <div class="grid two">
      <section class="panel">
        <h3>Sinais agora (gate 4)</h3>
        @if (latest(); as l) {
          <p class="muted small">
            Versão {{ l.model_version ?? '—' }} ·
            @if (l.age_minutes !== null) { previsão de {{ l.age_minutes }} min atrás } @else { nenhuma previsão ainda }
          </p>
          @if (signals().length === 0) {
            <p class="muted">Nenhum modelo com P ≥ 60% e margem ≥ 35% no último momento de decisão.</p>
          } @else {
            <div class="table-wrap"><table>
              <tr><th>Par</th><th>Modelo</th><th>Horizonte</th><th>Sinal</th><th>Probabilidades</th></tr>
              @for (p of signals(); track p.id) {
                <tr>
                  <td class="mono">{{ p.symbol }}</td>
                  <td>{{ names[p.model] ?? p.model }}</td>
                  <td>{{ p.horizonMin }} min</td>
                  <td><span class="badge" [class.buy]="p.signal === 'BUY'" [class.sell]="p.signal === 'SELL'">
                    {{ p.signal === 'BUY' ? 'COMPRA' : 'VENDA' }}</span></td>
                  <td><prob-bar [down]="p.pDown" [flat]="p.pFlat" [up]="p.pUp" /></td>
                </tr>
              }
            </table></div>
          }
          <h3 style="margin-top:16px">Mais provável por par (Eventos + Preço, 60 min)</h3>
          <div class="table-wrap"><table>
            <tr><th>Par</th><th>Momento</th><th>Probabilidades (queda · lateral · alta)</th></tr>
            @for (p of mainModel(); track p.id) {
              <tr>
                <td class="mono">{{ p.symbol }}</td>
                <td class="small muted nowrap">{{ p.momentUtc | date: 'dd/MM HH:mm' : 'UTC' }} {{ p.kind === 'EVENT' ? '· evento' : '' }}</td>
                <td><prob-bar [down]="p.pDown" [flat]="p.pFlat" [up]="p.pUp" /></td>
              </tr>
            } @empty {
              <tr><td colspan="3" class="muted">Sem previsões. Mercado fechado ou modelo ainda não treinado.</td></tr>
            }
          </table></div>
        } @else {
          <p class="muted">Carregando…</p>
        }
      </section>

      <section class="panel">
        <h3>Próximos eventos (24 h)</h3>
        <div class="table-wrap"><table>
          <tr><th>Quando (UTC)</th><th>Moeda</th><th>Evento</th><th class="right">Previsto</th><th class="right">Atual</th></tr>
          @for (e of events(); track e.value_id) {
            <tr [class.muted]="isPast(e)">
              <td class="nowrap mono small">{{ e.scheduled_at | date: 'dd/MM HH:mm' : 'UTC' }}</td>
              <td><span class="badge" [class.high]="e.importance === 'HIGH'">{{ e.currency }}</span></td>
              <td>{{ e.name }}</td>
              <td class="right mono">{{ e.forecast ?? '—' }}</td>
              <td class="right mono">{{ e.actual ?? '—' }}</td>
            </tr>
          } @empty {
            <tr><td colspan="5" class="muted">Nenhum evento de importância média ou alta nas próximas 24 h.</td></tr>
          }
        </table></div>
      </section>
    </div>

    <section class="panel" style="margin-top:16px">
      <h3>Últimas notícias dos bancos centrais</h3>
      <div class="table-wrap"><table>
        <tr><th>Visto em (UTC)</th><th>Emissor</th><th>Título</th><th>Leitura do Jev</th></tr>
        @for (n of news(); track n.id) {
          <tr>
            <td class="nowrap mono small">{{ n.first_seen_at | date: 'dd/MM HH:mm' : 'UTC' }}</td>
            <td class="nowrap">{{ n.issuer }} <span class="muted small">{{ n.currency }}</span></td>
            <td><a [href]="n.url" target="_blank" rel="noopener">{{ n.title }}</a></td>
            <td class="nowrap">
              @if (n.jev; as j) {
                <span class="badge" [class.buy]="j.stance > 0.15" [class.sell]="j.stance < -0.15">
                  {{ j.stance > 0.15 ? 'hawkish' : j.stance < -0.15 ? 'dovish' : 'neutro' }}
                  {{ j.stance | number: '1.2-2' }}</span>
                <span class="muted small"> relev. {{ j.relevance | number: '1.1-1' }}</span>
              } @else {
                <span class="muted small">não avaliado</span>
              }
            </td>
          </tr>
        } @empty {
          <tr><td colspan="4" class="muted">Sem documentos.</td></tr>
        }
      </table></div>
    </section>
  `,
})
export class Hoje implements OnInit, OnDestroy {
  private api = inject(Api);
  protected names = MODEL_NAMES;
  protected latest = signal<LatestPredictions | null>(null);
  protected events = signal<CalendarItem[]>([]);
  protected news = signal<NewsItem[]>([]);
  protected signals = computed(() => (this.latest()?.predictions ?? []).filter((p) => p.signal !== 'NO_TRADE'));
  protected mainModel = computed(() =>
    (this.latest()?.predictions ?? []).filter((p) => p.model === 'B' && p.horizonMin === 60));
  private timer?: ReturnType<typeof setInterval>;

  ngOnInit(): void {
    this.load();
    this.timer = setInterval(() => this.load(), 60_000);
  }

  ngOnDestroy(): void {
    clearInterval(this.timer);
  }

  protected isPast(e: CalendarItem): boolean {
    return new Date(e.scheduled_at).getTime() < Date.now();
  }

  private load(): void {
    this.api.latest().subscribe({ next: (l) => this.latest.set(l), error: () => this.latest.set(null) });
    this.api.upcoming(24).subscribe({ next: (e) => this.events.set(e), error: () => this.events.set([]) });
    this.api.news(25).subscribe({ next: (n) => this.news.set(n), error: () => this.news.set([]) });
  }
}
