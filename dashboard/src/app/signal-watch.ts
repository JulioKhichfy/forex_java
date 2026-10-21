import { Component, OnDestroy, OnInit, inject, signal } from '@angular/core';
import { DatePipe, DecimalPipe } from '@angular/common';
import { HttpClient } from '@angular/common/http';
import { Router } from '@angular/router';
import { MODEL_NAMES, Prediction } from './api';
import { MARKETS, MarketState } from './market';
import { Brt } from './brt';

interface SignalItem { market: string; prediction: Prediction }

/**
 * Vigia de sinais (modo só sinais, 20/10/2026): em QUALQUER página, consulta a cada 20 s os sinais das regras de mercado
 * (ouro e índices) e avisa — faixa no topo, bipe e notificação do Windows, uma vez por previsão. "Ver e operar" leva à
 * página Hoje do mercado, onde está o botão Operar agora (todas as travas valem).
 */
@Component({
  selector: 'signal-watch',
  imports: [DatePipe, DecimalPipe, Brt],
  template: `
    @for (s of shown(); track s.prediction.id) {
      @let p = s.prediction;
      <div class="sig" [class.buy]="p.signal === 'BUY'" [class.sell]="p.signal === 'SELL'">
        <b>SINAL {{ p.signal === 'BUY' ? 'DE COMPRA' : 'DE VENDA' }} · {{ p.symbol }}</b>
        <span class="small">{{ label(s.market) }} · {{ names[p.model] ?? p.model }} {{ p.horizonMin }} min ·
          <span [brt]="p.momentUtc">{{ p.momentUtc | date: 'HH:mm' : 'UTC' }} UTC</span> ({{ age(p) }} min) ·
          P(alta) {{ p.pUp * 100 | number: '1.0-0' }}% · P(queda) {{ p.pDown * 100 | number: '1.0-0' }}%</span>
        <span class="small">Vale enquanto for recente: entrar atrasado leva a vantagem embora. Com o sinal automático ligado
          (página Hoje), a ordem já foi enviada sozinha: confira lá se saiu ou o motivo da recusa.</span>
        <button class="primary" (click)="open(s)">Ver e operar</button>
        <button (click)="dismiss(p.id)" title="Esconder este aviso">×</button>
      </div>
    }
  `,
  styles: `
    .sig { display: flex; gap: 10px; align-items: center; flex-wrap: wrap; padding: 8px 16px; border-bottom: 2px solid var(--border);
      background: var(--panel); }
    .sig.buy { border-color: var(--up); background: var(--up-bg); }
    .sig.sell { border-color: var(--down); background: var(--down-bg); }
  `,
})
export class SignalWatch implements OnInit, OnDestroy {
  private http = inject(HttpClient);
  private router = inject(Router);
  private marketState = inject(MarketState);
  protected names = MODEL_NAMES;
  protected shown = signal<SignalItem[]>([]);
  private dismissed = new Set<number>();
  private alerted = new Set<number>();
  private timer?: ReturnType<typeof setInterval>;

  ngOnInit(): void {
    try { JSON.parse(localStorage.getItem('jev.signals.alerted') ?? '[]').forEach((id: number) => this.alerted.add(id)); } catch { /* sem armazenamento */ }
    this.poll();
    this.timer = setInterval(() => this.poll(), 20_000);
  }

  ngOnDestroy(): void {
    clearInterval(this.timer);
  }

  private poll(): void {
    this.http.get<{ signals: SignalItem[] }>('/api/predictions/signals?minutes=15').subscribe({
      next: (r) => {
        const list = (r.signals ?? []).filter((s) => !this.dismissed.has(s.prediction.id) && this.age(s.prediction) <= 10)
          .sort((a, b) => b.prediction.momentUtc.localeCompare(a.prediction.momentUtc));
        this.shown.set(list);
        list.filter((s) => !this.alerted.has(s.prediction.id)).forEach((s) => this.notify(s));
      },
      error: () => {},
    });
  }

  protected age(p: Prediction): number {
    return Math.max(0, Math.floor((Date.now() - new Date(p.momentUtc).getTime()) / 60_000));
  }

  protected label(market: string): string {
    return MARKETS.find((m) => m.code === market)?.label ?? market;
  }

  protected dismiss(id: number): void {
    this.dismissed.add(id);
    this.shown.set(this.shown().filter((s) => s.prediction.id !== id));
  }

  protected open(s: SignalItem): void {
    this.marketState.set(s.market);
    this.router.navigate(['/']);
  }

  /** Bipe e notificação do Windows, uma vez por previsão (lembrado no navegador para não repetir ao recarregar). */
  private notify(s: SignalItem): void {
    const p = s.prediction;
    this.alerted.add(p.id);
    try { localStorage.setItem('jev.signals.alerted', JSON.stringify([...this.alerted].slice(-200))); } catch { /* sem armazenamento */ }
    const text = `${p.signal === 'BUY' ? 'COMPRA' : 'VENDA'} ${p.symbol} (${this.label(s.market)}) · ${p.horizonMin} min`;
    try {
      if ('Notification' in window) {
        if (Notification.permission === 'granted') new Notification('Jev Forex: sinal', { body: text });
        else if (Notification.permission !== 'denied') Notification.requestPermission();
      }
    } catch { /* sem aviso do navegador: fica a faixa */ }
    try {
      const ctx = new AudioContext();
      const o = ctx.createOscillator();
      o.frequency.value = 880;
      o.connect(ctx.destination);
      o.start();
      o.stop(ctx.currentTime + 0.25);
    } catch { /* sem som */ }
  }
}
