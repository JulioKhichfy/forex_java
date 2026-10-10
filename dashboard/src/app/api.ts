import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

/** Nomes dos 3 modelos do dashboard (códigos do jev-ml: A, E, B). */
export const MODEL_NAMES: Partial<Record<string, string>> = {
  A: 'Preço',
  E: 'Eventos',
  B: 'Eventos + Preço',
  C: 'Eventos + Preço + Texto',
  D: 'Eventos + Preço + Surpresa de tom',
};
export const MODEL_ORDER = ['A', 'E', 'B'];

export interface Prediction {
  id: number;
  market: string;
  symbol: string;
  momentUtc: string;
  kind: 'EVENT' | 'CONTROL';
  trigger: 'SCHEDULE' | 'EVENT' | 'MANUAL';
  model: string;
  horizonMin: number;
  modelVersion: string;
  pDown: number;
  pFlat: number;
  pUp: number;
  signal: 'BUY' | 'SELL' | 'NO_TRADE';
  atr: number | null;
  close: number | null;
  spreadPoints: number | null;
  dataLagS: number | null;
  createdAt: string;
  resolvedAt: string | null;
  label: string | null;
  rBuy: number | null;
  rSell: number | null;
}

export interface LatestPredictions {
  model_version: string | null;
  trained_until: string | null;
  lockbox_run: string | null;
  age_minutes: number | null;
  predictions: Prediction[];
}

export interface Score {
  model: string;
  horizonMin: number;
  n: number;
  hits: number;
  sumR: number;
  resolved: number;
  directional: number;
}

export interface RunResult {
  at: string;
  trigger: string;
  modelVersion: string | null;
  rows: number;
  symbols: string[];
  lastBarUtc: string | null;
  note: string | null;
}

export interface CalendarItem {
  value_id: number;
  event_code: string;
  name: string;
  currency: string;
  importance: string;
  scheduled_at: string;
  actual: number | null;
  forecast: number | null;
  previous: number | null;
}

export interface NewsItem {
  id: number;
  issuer: string;
  currency: string;
  feed: string;
  title: string;
  url: string;
  published_at: string | null;
  first_seen_at: string;
  jev: { qset: string; stance: number; signal: number; relevance: number; guidance_change: number | null } | null;
}

export interface Trades {
  n: number;
  expectancyR: number;
  profitFactor: number;
  maxDrawdownPct: number;
  hitRate: number;
}

export interface ModelSummary {
  ll: number;
  llEvent: number;
  aucUp: number;
  aucDown: number;
  aucUpEvent: number;
  trades: Trades;
  tradesLate: Trades;
  tradesCost: Trades;
}

export interface HorizonSummary {
  horizon: number;
  first_test: string;
  last_test: string;
  folds: number;
  rows: number;
  ll_base: number;
  models: Record<string, ModelSummary>;
  comparisons: Record<string, number>;
  sweep: { minProb: number; minMargin: number; models: Record<string, Trades> }[];
}

export interface ModelsInfo {
  champion: { version: string; promotedAt: string; note: string } | null;
  versions: { version: string; trained_at: string; train_from: string; train_to: string; lockbox_run: string | null;
    entries: { model: string; horizon: number; rows: number; features: number }[] }[];
  lockbox: { opened_at: string; run?: string; status: string } | null;
  lockbox_summary?: HorizonSummary[];
  walkforward_run?: string;
  walkforward_summary?: HorizonSummary[];
}

export interface Check {
  gate: string;
  ok: boolean;
  value: string;
  limit: string;
}

export interface OrderPreview {
  symbol: string;
  side: 'BUY' | 'SELL';
  lot: number;
  refPrice: number | null;
  atr: number | null;
  slDistance: number | null;
  tpDistance: number | null;
  slPrice: number | null;
  tpPrice: number | null;
  riskUsd: number | null;
  riskPct: number | null;
  rewardUsd: number | null;
  balance: number | null;
  equity: number | null;
  closeAfterMinutes: number | null;
  mode: string;
  predictionId: number | null;
  checks: Check[];
  ok: boolean;
}

export interface Submit {
  orderId: number | null;
  status: string;
  preview: OrderPreview;
}

export interface Position {
  ticket: number;
  symbol: string;
  side: 'BUY' | 'SELL';
  volume: number;
  openPrice: number;
  sl: number | null;
  tp: number | null;
  profit: number | null;
  openTime: string | null;
  reportedAt: string;
}

export interface Order {
  id: number;
  symbol: string;
  action: 'OPEN' | 'CLOSE';
  side: string | null;
  volume: number | null;
  riskUsd: number | null;
  status: string;
  message: string | null;
  fillPrice: number | null;
  ticket: number | null;
  createdAt: string;
  closeAfterUtc: string | null;
}

export interface TradeState {
  mode: string;
  account: { account: number; server: string; tradeMode: string; equity: number | null; balance: number | null;
    reportedAt: string | null; detailJson: string | null } | null;
  positions: Position[];
  orders: Order[];
  lot: number;
  blocked: boolean;
  flattening: boolean;
  symbols: string[];
  limits: {
    global: { maxRiskPerTradePct: number; maxRiskPerTradeUsd: number; maxDailyLossPct: number; maxPositions: number };
    exits: { stopAtr: number; targetR: number };
    orders: { defaultLot: number; maxLot: number; closeAfterMinutes: number; validitySeconds: number };
  };
}

@Injectable({ providedIn: 'root' })
export class Api {
  tradeState(): Observable<TradeState> {
    return this.http.get<TradeState>('/api/trade/state');
  }

  preview(symbol: string, side: string, lot: number): Observable<OrderPreview> {
    return this.http.post<OrderPreview>('/api/trade/preview', { symbol, side, lot });
  }

  submit(symbol: string, side: string, lot: number): Observable<Submit> {
    return this.http.post<Submit>('/api/trade/orders', { symbol, side, lot });
  }

  closePosition(ticket: number): Observable<{ orderId: number }> {
    return this.http.post<{ orderId: number }>(`/api/trade/close/${ticket}`, {});
  }

  flatten(): Observable<unknown> {
    return this.http.post('/api/trade/flatten', {});
  }

  block(on: boolean): Observable<unknown> {
    return this.http.post(`/api/trade/block?on=${on}`, {});
  }

  setLot(value: number): Observable<{ lot: number }> {
    return this.http.post<{ lot: number }>(`/api/trade/lot?value=${value}`, {});
  }

  private http = inject(HttpClient);

  status(): Observable<Record<string, unknown>> {
    return this.http.get<Record<string, unknown>>('/api/status');
  }

  latest(): Observable<LatestPredictions> {
    return this.http.get<LatestPredictions>('/api/predictions/latest');
  }

  history(symbol: string | null, hours = 48): Observable<Prediction[]> {
    const q = symbol ? `symbol=${encodeURIComponent(symbol)}&` : '';
    return this.http.get<Prediction[]>(`/api/predictions?${q}hours=${hours}`);
  }

  scoreboard(days = 90): Observable<{ since: string; models: Score[] }> {
    return this.http.get<{ since: string; models: Score[] }>(`/api/predictions/scoreboard?days=${days}`);
  }

  runNow(): Observable<RunResult> {
    return this.http.post<RunResult>('/api/predictions/run', {});
  }

  upcoming(hours = 24): Observable<CalendarItem[]> {
    return this.http.get<CalendarItem[]>(`/api/calendar/upcoming?hours=${hours}`);
  }

  news(limit = 30): Observable<NewsItem[]> {
    return this.http.get<NewsItem[]>(`/api/news?limit=${limit}`);
  }

  models(): Observable<ModelsInfo> {
    return this.http.get<ModelsInfo>('/api/models');
  }
}
