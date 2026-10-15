import { Injectable, signal } from '@angular/core';

export const MARKETS: { code: string; label: string }[] = [
  { code: 'fx', label: 'Forex' },
  { code: 'indices', label: 'Índices' },
  { code: 'stocks', label: 'Ações' },
  { code: 'metals', label: 'Ouro' },
  { code: 'crypto', label: 'Bitcoin' },
  { code: 'energy', label: 'Energia' },
];

/** Mercado escolhido no topo do dashboard (lembrado no navegador). */
@Injectable({ providedIn: 'root' })
export class MarketState {
  readonly market = signal<string>(this.load());

  set(code: string): void {
    this.market.set(code);
    try { localStorage.setItem('jev.market', code); } catch { /* sem armazenamento: só nesta aba */ }
  }

  private load(): string {
    try { return localStorage.getItem('jev.market') ?? 'fx'; } catch { return 'fx'; }
  }
}
