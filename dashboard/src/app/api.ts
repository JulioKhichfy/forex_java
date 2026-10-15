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

/** Regra de sinal validada para um mercado (live.signal-rules); null = gate 4. */
export interface SignalRule {
  model: string;
  horizon: number;
  minProb: number;
  minMargin: number;
  eventsOnly: boolean;
  note: string | null;
}

export interface LatestPredictions {
  model_version: string | null;
  trained_until: string | null;
  lockbox_run: string | null;
  age_minutes: number | null;
  rule: SignalRule | null;
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
    challenge: Challenge | null;
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
  action: 'OPEN' | 'CLOSE' | 'MODIFY';
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

/** De onde veio a posição e como ela está protegida (GET /api/trade/state → details[ticket]). */
export interface PositionDetail {
  ticket: number;
  origin: 'roleta' | 'manual' | 'externa';
  strategy: 'trend' | 'pullback' | null;
  model: string | null;
  horizonMin: number | null;
  pSide: number | null;
  pctB: number | null;
  openedAt: string | null;
  closeAfterUtc: string | null;
  timeLockUntil: string | null;
  timeLockStatus: string | null;
  timeLockMessage: string | null;
  stopMode: 'ATR' | 'USD' | null;
  stopAtr: number | null;
  targetR: number | null;
  riskUsdPlanned: number | null;
  slUsd: number | null;
  tpUsd: number | null;
  trailMoves: number;
  trailLast: string | null;
  timeLockStartedAt: string | null;
  timeLockPeakUsd: number | null;
  timeLockPeakAt: string | null;
  timeLockMoves: number | null;
}

/** trading.risk.money: stop/alvo em US$, trailing e prorrogação com lucro. */
export interface MoneyExits {
  enabled: boolean;
  stopUsd: number;
  takeUsd: number;
  trailEnabled: boolean;
  trailStartUsd: number;
  trailDistanceUsd: number;
  trailStepUsd: number;
  timeLockEnabled: boolean;
  timeLockMinProfitUsd: number;
  timeLockKeepPct: number;
  timeLockExtraPct: number;
  timeLockMinutes: number;
  timeLockMaxMinutes: number;
  timeLockStepPct: number;
}

export interface TradeState {
  mode: string;
  account: { account: number; server: string; tradeMode: string; equity: number | null; balance: number | null;
    reportedAt: string | null; detailJson: string | null } | null;
  positions: Position[];
  details?: Record<string, PositionDetail>;
  money?: MoneyExits;
  orders: Order[];
  lot: number;
  blocked: boolean;
  flattening: boolean;
  loss_limits?: { label: string; maxDailyLossPct: number; maxWeeklyLossPct: number };
  symbols: string[];
  symbols_by_market: Record<string, string[]>;
  limits: {
    global: { maxRiskPerTradePct: number; maxRiskPerTradeUsd: number; maxDailyLossPct: number; maxPositions: number };
    exits: { stopAtr: number; targetR: number };
    orders: { defaultLot: number; maxLot: number; closeAfterMinutes: number; validitySeconds: number };
  };
}

export interface JobDef {
  name: string;
  label: string;
  help: string;
}

export interface JobStatus {
  id: string;
  name: string;
  label: string;
  startedAt: string;
  finishedAt: string | null;
  step: string | null;
  exitCode: number | null;
  running: boolean;
  tail: string[];
  logFile: string;
}

export interface ChallengeRow {
  model: string;
  horizon: number;
  rows: number;
  llChampion: number;
  llChallenger: number;
  tradesChampion: Trades | null;
  tradesChallenger: Trades | null;
}

export interface Challenge {
  month: string;
  championVersion: string;
  championOutOfSample: boolean;
  mainModel: string;
  rows: ChallengeRow[];
  recommended: boolean;
  reason: string;
}

/** Radar (GET /api/scanner): descrição do que o preço está fazendo; nada aqui é sinal validado. */
export interface ScanAlert {
  kind: string;
  tf: string;
  severity: number;
  dir: 'up' | 'down' | null;
  text: string;
  value: number;
  at: string | null;
}

export interface ScanTf {
  tf: string;
  barUtc: string | null;
  close: number | null;
  rsi: number | null;
  rsiNow: number | null;
  pctB: number | null;
  upper: number | null;
  mid: number | null;
  lower: number | null;
  bandwidth: number | null;
  squeeze: boolean;
  atr: number | null;
  volRatio: number | null;
  streak: number;
  trend: 'up' | 'down' | null;
}

export interface ScanMove {
  window: string;
  pct: number | null;
  atrs: number | null;
}

export interface ScanSymbol {
  symbol: string;
  currencies: string[];
  lastBarUtc: string | null;
  price: number | null;
  spreadNow: number | null;
  spreadRatio: number | null;
  moves: ScanMove[];
  tfs: ScanTf[];
  day: { open: number | null; high: number | null; low: number | null; prevHigh: number | null; prevLow: number | null;
    high20: number | null; low20: number | null; adr: number | null; rangeAdr: number | null };
  alerts: ScanAlert[];
}

export interface ScanSnapshot {
  market: string;
  generatedAt: string;
  tookMs: number;
  recentFiles: number;
  lastBarUtc: string | null;
  symbols: ScanSymbol[];
  strength: Record<string, Record<string, number>>;
}

/** Roleta (GET /api/roulette). */
export interface RouletteConfig {
  on: boolean;
  horizon: number;
  model: string;
  lot: number | null;
  startedAt: string | null;
  stoppedAt: string | null;
  stopReason: string | null;
  strategy: 'trend' | 'pullback' | 'both';
  band: number;
}

export interface RouletteEntry {
  id: number;
  market: string;
  symbol: string;
  model: string;
  horizon_min: number;
  strategy: 'trend' | 'pullback';
  pct_b: number | null;
  side: 'BUY' | 'SELL' | null;
  p_side: number | null;
  status: 'SENDING' | 'SENT' | 'SKIPPED' | 'REFUSED';
  message: string | null;
  created_at: string;
  moment_utc: string;
  order_id: number | null;
  order_status: string | null;
  order_message: string | null;
  fill_price: number | null;
  ticket: number | null;
  risk_usd: number | null;
  close_after_utc: string | null;
}

export interface RouletteState {
  mode: string;
  blocked: boolean;
  loss_profile: { label: string; maxDailyLossPct: number; maxWeeklyLossPct: number };
  markets: Record<string, RouletteConfig>;
  max_lot: number;
  entry_max_age_minutes: number;
  counts: { market: string; strategy: string; sent: number; skipped: number; refused: number }[];
  closed: { market: string; strategy: string; trades: number; result_usd: number | null; wins: number; avg_r: number | null;
    gains: number | null; losses: number | null }[];
  open: { market: string; strategy: string; positions: number; risk_usd: number | null; profit_usd: number | null }[];
  simulated: { market: string; model: string; horizon_min: number; n: number; avg_r: number; sum_r: number;
    hit_rate: number; since: string }[];
  entries: RouletteEntry[];
}

/** Backtest das previsões resolvidas (GET /api/predictions/backtest). */
export interface BacktestResult {
  from: string;
  trades: number;
  wins: number;
  losses: number;
  sumR: number;
  avgR: number | null;
  usd: number;
  pending: number;
  skippedFlat: number;
  filtered: number;
  moneyStop: boolean;
  bySymbol: { symbol: string; trades: number; wins: number; losses: number; sumR: number; usd: number }[];
  list: { moment: string; symbol: string; side: 'BUY' | 'SELL'; pSide: number; label: string; r: number; usd: number | null }[];
}

export interface BacktestQuery {
  market: string;
  symbol: string | null;
  period: string;
  horizon: number;
  model: string;
  lot: number;
  side: string;
  minProb: number;
  skipFlat: boolean;
}

@Injectable({ providedIn: 'root' })
export class Api {
  backtest(q: BacktestQuery): Observable<BacktestResult> {
    const p = new URLSearchParams({ market: q.market, period: q.period, horizon: String(q.horizon), model: q.model,
      lot: String(q.lot), side: q.side, minProb: String(q.minProb), skipFlat: String(q.skipFlat) });
    if (q.symbol) p.set('symbol', q.symbol);
    return this.http.get<BacktestResult>(`/api/predictions/backtest?${p}`);
  }

  roulette(days = 30): Observable<RouletteState> {
    return this.http.get<RouletteState>(`/api/roulette?days=${days}`);
  }

  rouletteStart(market: string, horizon: number, model: string, lot: number | null, strategy = 'trend',
                band = 0.1): Observable<RouletteConfig> {
    return this.http.post<RouletteConfig>(`/api/roulette/start?market=${market}&horizon=${horizon}&model=${model}`
      + `&strategy=${strategy}&band=${band}` + (lot ? `&lot=${lot}` : ''), {});
  }

  rouletteStop(market: string | null = null): Observable<unknown> {
    return this.http.post(`/api/roulette/stop${market ? '?market=' + market : ''}`, {});
  }

  scanner(market = 'fx'): Observable<ScanSnapshot> {
    return this.http.get<ScanSnapshot>(`/api/scanner?market=${market}`);
  }

  jobs(): Observable<{ available: JobDef[]; current: JobStatus | null }> {
    return this.http.get<{ available: JobDef[]; current: JobStatus | null }>('/api/ops/jobs');
  }

  startJob(name: string): Observable<JobStatus> {
    return this.http.post<JobStatus>(`/api/ops/jobs/${name}`, {});
  }

  promote(version: string, market = 'fx'): Observable<unknown> {
    return this.http.post(`/api/ops/promote?version=${encodeURIComponent(version)}&market=${market}`, {});
  }

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


  setTimeLock(on: boolean): Observable<{ money: MoneyExits }> {
    return this.http.post<{ money: MoneyExits }>(`/api/trade/time-lock?on=${on}`, {});
  }

  setLot(value: number): Observable<{ lot: number }> {
    return this.http.post<{ lot: number }>(`/api/trade/lot?value=${value}`, {});
  }

  private http = inject(HttpClient);

  status(): Observable<Record<string, unknown>> {
    return this.http.get<Record<string, unknown>>('/api/status');
  }

  latest(market = 'fx'): Observable<LatestPredictions> {
    return this.http.get<LatestPredictions>(`/api/predictions/latest?market=${market}`);
  }

  history(symbol: string | null, hours = 48, market = 'fx'): Observable<Prediction[]> {
    const q = symbol ? `symbol=${encodeURIComponent(symbol)}&` : '';
    return this.http.get<Prediction[]>(`/api/predictions?${q}hours=${hours}&market=${market}`);
  }

  scoreboard(days = 90, market = 'fx'): Observable<{ since: string; models: Score[] }> {
    return this.http.get<{ since: string; models: Score[] }>(`/api/predictions/scoreboard?days=${days}&market=${market}`);
  }

  runNow(): Observable<RunResult> {
    return this.http.post<RunResult>('/api/predictions/run', {});
  }

  upcoming(hours = 24, currency: string | null = null): Observable<CalendarItem[]> {
    return this.http.get<CalendarItem[]>(`/api/calendar/upcoming?hours=${hours}${currency ? '&currency=' + currency : ''}`);
  }

  news(limit = 30, currency: string | null = null): Observable<NewsItem[]> {
    return this.http.get<NewsItem[]>(`/api/news?limit=${limit}${currency ? '&currency=' + currency : ''}`);
  }

  models(market = 'fx'): Observable<ModelsInfo> {
    return this.http.get<ModelsInfo>(`/api/models?market=${market}`);
  }
}
