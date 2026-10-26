import { Component, OnDestroy, OnInit, inject, input, signal } from '@angular/core';
import { DatePipe, DecimalPipe } from '@angular/common';
import { HttpClient } from '@angular/common/http';
import { Tip } from './tip';
import { Brt } from '../brt';
import { ORIGINS } from '../api';

interface ClosedTrade {
  positionTicket: number;
  market: string;
  symbol: string;
  side: 'BUY' | 'SELL' | null;
  volume: number;
  entryPrice: number | null;
  exitPrice: number | null;
  openedAt: string | null;
  closedAt: string;
  reason: string;
  resultUsd: number;
  riskUsd: number | null;
  r: number | null;
  origin: string | null;
  originNote: string | null;
}

interface Performance {
  days: number;
  summary: { trades: number; wins: number; result_usd: number; profit_factor: number | null; avg_r: number | null; hit_rate: number | null };
  trades: ClosedTrade[];
}

const REASONS: Record<string, string> = {
  SL: 'stop (servidor)', TP: 'alvo (servidor)', EA: 'botão / saída por tempo', MANUAL: 'manual no MT5', MANUAL_CELULAR: 'manual no celular', MANUAL_WEB: 'manual no terminal web', STOP_OUT: 'stop-out da corretora',
};

/** Placar das operações do operador: o que de fato aconteceu com o dinheiro (inclui SL/TP feitos pela corretora). */
@Component({
  selector: 'my-trades',
  imports: [DatePipe, DecimalPipe, Tip, Brt],
  template: `
    <h3>Suas operações ({{ days() }} dias) <tip text="O resultado REAL das ordens que você enviou, inclusive as que a corretora fechou sozinha no stop ou no alvo. Diferente do placar dos modelos, que mede as previsões." /></h3>
    @if (perf(); as p) {
      <div class="cards">
        <div><div class="muted small">Operações</div><b>{{ p.summary.trades }}</b></div>
        <div><div class="muted small">Acerto <tip key="acerto" /></div><b>{{ p.summary.hit_rate === null ? '—' : (p.summary.hit_rate * 100 | number: '1.0-0') + '%' }}</b></div>
        <div><div class="muted small">Resultado</div><b [class.up]="p.summary.result_usd > 0" [class.down]="p.summary.result_usd < 0">US$ {{ p.summary.result_usd | number: '1.2-2' }}</b></div>
        <div><div class="muted small">R médio <tip text="Resultado médio dividido pela perda máxima planejada em cada entrada. +0,10 R por operação é o mínimo que o documento mestre pede." /></div>
          <b [class.up]="(p.summary.avg_r ?? 0) > 0" [class.down]="(p.summary.avg_r ?? 0) < 0">{{ p.summary.avg_r === null ? '—' : (p.summary.avg_r | number: '1.2-2') }}</b></div>
        <div><div class="muted small">Profit factor <tip key="pf" /></div><b>{{ p.summary.profit_factor === null ? '—' : (p.summary.profit_factor | number: '1.2-2') }}</b></div>
      </div>
      @if (!compact()) {
        <div class="table-wrap"><table>
          <tr><th>Fechada (UTC)</th><th>Símbolo</th><th>Lado</th>
            <th>Origem <tip text="Quem abriu a operação: Manual (botão), Roleta, Sinal automático, Momentum BTC, Cassino ou fora do sistema, com um detalhe da entrada." /></th><th class="right">Lote</th><th class="right">Entrada → saída</th>
            <th>Saída <tip text="Quem fechou: stop ou alvo no servidor da corretora, o sistema (botão ou saída por tempo), você no MT5, ou a corretora por falta de margem." /></th>
            <th class="right">Resultado</th><th class="right">R <tip key="R" /></th></tr>
          @for (t of p.trades; track t.positionTicket) {
            <tr>
              <td class="small mono nowrap" [brt]="t.closedAt">{{ t.closedAt | date: 'dd/MM HH:mm' : 'UTC' }}</td>
              <td class="mono">{{ t.symbol }}</td>
              <td>@if (t.side) { <span class="badge" [class.buy]="t.side === 'BUY'" [class.sell]="t.side === 'SELL'">{{ t.side === 'BUY' ? 'COMPRA' : 'VENDA' }}</span> }</td>
              <td class="small">{{ origins[t.origin ?? ''] ?? t.origin ?? '—' }}@if (t.originNote) { <span class="muted"> · {{ t.originNote }}</span> }</td>
              <td class="right mono">{{ t.volume }}</td>
              <td class="right mono small">{{ t.entryPrice ?? '—' }} → {{ t.exitPrice }}</td>
              <td class="small">{{ reasons[t.reason] ?? t.reason }}</td>
              <td class="right mono" [class.up]="t.resultUsd > 0" [class.down]="t.resultUsd < 0">US$ {{ t.resultUsd | number: '1.2-2' }}</td>
              <td class="right mono">{{ t.r === null ? '—' : (t.r | number: '1.2-2') }}</td>
            </tr>
          } @empty {
            <tr><td colspan="9" class="muted">Nenhuma operação fechada no período (o EA 2.1 reporta os fechamentos a cada heartbeat).</td></tr>
          }
        </table></div>
      }
    } @else {
      <p class="muted">Carregando…</p>
    }
  `,
  styles: `
    .cards { display: grid; grid-template-columns: repeat(auto-fit, minmax(110px, 1fr)); gap: 10px; margin-bottom: 10px; }
    .cards > div { background: var(--panel-2); border-radius: 8px; padding: 8px 10px; }
    .cards b { font-size: 18px; }
  `,
})
export class MyTrades implements OnInit, OnDestroy {
  private http = inject(HttpClient);
  days = input(30);
  compact = input(false);
  protected reasons: Partial<Record<string, string>> = REASONS;
  protected origins: Partial<Record<string, string>> = ORIGINS;
  protected perf = signal<Performance | null>(null);
  private timer?: ReturnType<typeof setInterval>;

  ngOnInit(): void {
    this.load();
    this.timer = setInterval(() => this.load(), 15_000);
  }

  ngOnDestroy(): void {
    clearInterval(this.timer);
  }

  private load(): void {
    this.http.get<Performance>(`/api/trade/performance?days=${this.days()}`).subscribe({
      next: (p) => this.perf.set(p), error: () => {},
    });
  }
}
