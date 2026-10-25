"""
Momentum após hora grande (passo 12b, 25/10/2026): o que sobrou do estudo do mapa de liquidações (tools/estudo_liquidacoes.py)
foi que, no bitcoin da Binance, SEGUIR o movimento depois de uma hora de movimento muito grande dava E[R] positivo — com
ou sem o mapa. Aqui a regra de preço pura, com os candles M1 da Exness (silver do lake) de todos os mercados.

Regra (point-in-time): na hora cheia, se |retorno da última hora| ≥ o quantil (1 − q) das últimas 720 horas do símbolo,
entra no minuto seguinte A FAVOR (controle: CONTRA). Stop k × ATR(14) de 1 h, alvo R × stop, saída por tempo; uma
operação por vez por símbolo; alvo e stop no mesmo minuto = stop. Custo: o spread da Exness no minuto da entrada.
Escolha até 2025-06, confirmação depois (o mesmo corte do Laboratório).

Uso:  python tools/estudo_momentum.py [--lake …] [--from 2022-01-01] [--markets fx,metals,crypto,indices,energy,stocks]
Saída: reports/momentum/report.json e o resumo no terminal.
"""
import argparse
import datetime as dt
import glob
import json
import math
import os
import sys
import time

import duckdb
import numpy as np

CHOOSE_END = np.datetime64("2025-07-01T00:00")
QS = (0.01, 0.02, 0.05)
STOPS = (1.0, 1.5, 2.0)
TARGETS = (1.0, 1.5, 2.0)
HOLDS = (240, 1440)
DEFAULT_SYMBOLS = {
    "fx": ["EURUSD", "GBPUSD", "USDJPY", "USDCHF", "AUDUSD", "USDCAD", "NZDUSD"],
    "metals": ["XAUUSD"], "crypto": ["BTCUSD"], "energy": ["UKOIL", "USOIL", "XNGUSD"],
    "indices": ["AUS200", "DE30", "FR40", "HK50", "JP225", "STOXX50", "UK100", "US30", "US500", "USTEC", "IN50"],
    "stocks": ["AAPL", "MSFT", "NVDA", "META", "GOOGL", "INTC", "SPCX"],
}


def points(lake):
    """Tamanho do ponto por símbolo (spread vem em pontos), da especificação mais recente."""
    files = [f.replace(os.sep, "/") for f in glob.glob(f"{lake}/silver/instrument_specs/**/*.parquet", recursive=True)]
    con = duckdb.connect()
    rows = con.sql(f"""SELECT symbol, arg_max(point, seen_utc) FROM read_parquet({files!r}) GROUP BY 1""").fetchall()
    return {s: float(p) for s, p in rows}


def load(lake, market, symbol, start):
    files = [f.replace(os.sep, "/") for f in
             glob.glob(f"{lake}/silver/candles_m1/market={market}/symbol={symbol}/**/*.parquet", recursive=True)]
    if not files:
        return None
    return duckdb.connect().sql(f"""
        SELECT time_utc AS t, open o, high h, low l, close c, spread_points sp FROM read_parquet({files!r})
         WHERE time_utc >= TIMESTAMP '{start}' ORDER BY t""").fetchnumpy()


def trade(o, h, l, c, i0, side, stop, target, end):
    """(R bruto, índice da saída). Alvo e stop no mesmo minuto = stop."""
    e = o[i0]
    if side > 0:
        hs = np.flatnonzero(l[i0:end] <= e - stop)
        ht = np.flatnonzero(h[i0:end] >= e + target)
    else:
        hs = np.flatnonzero(h[i0:end] >= e + stop)
        ht = np.flatnonzero(l[i0:end] <= e - target)
    js = hs[0] if len(hs) else None
    jt = ht[0] if len(ht) else None
    if js is not None and (jt is None or js <= jt):
        return -1.0, i0 + js
    if jt is not None:
        return target / stop, i0 + jt
    return side * (c[end - 1] - e) / stop, end - 1


def study_symbol(k, point):
    t = k["t"].astype("datetime64[m]")
    o, h, l, c, sp = k["o"], k["h"], k["l"], k["c"], k["sp"].astype(float)
    n = len(t)
    hr = t.astype("datetime64[h]")
    # fim de cada hora com barras: o último minuto daquela hora
    last = np.flatnonzero(np.r_[hr[1:] != hr[:-1], True])
    first = np.r_[0, last[:-1] + 1]
    hh = np.maximum.reduceat(h, first)
    hl = np.minimum.reduceat(l, first)
    hc = c[last]
    ho = o[first]
    ret = np.log(hc / ho)    # o movimento DENTRO da hora (não atravessa fim de semana)
    prev = np.r_[hc[0], hc[:-1]]
    tr = np.maximum(hh - hl, np.maximum(abs(hh - prev), abs(hl - prev)))
    atr = np.convolve(tr, np.ones(14) / 14)[:len(tr)]
    full = (last - first + 1) >= 45   # hora quase completa (fora de abertura/fechamento de pregão)
    events = {}
    for q in QS:
        ev = []
        for j in range(720, len(last) - 1):
            if not full[j] or atr[j] <= 0:
                continue
            thr = np.quantile(np.abs(ret[j - 720:j]), 1 - q)
            if abs(ret[j]) >= thr and abs(ret[j]) > 0:
                ev.append(j)
        events[q] = ev
    out = []
    for q, ev in events.items():
        for stop_atr in STOPS:
            for tgt in TARGETS:
                for hold in HOLDS:
                    for mode in ("a favor", "contra"):
                        res = {"escolha": [], "confirmação": []}
                        busy = -1
                        for j in ev:
                            i0 = last[j] + 1
                            if i0 <= busy or i0 >= n:
                                continue
                            # a saída por tempo é em minutos de relógio; barras de mercado fechado não existem
                            end = int(np.searchsorted(t, t[i0] + np.timedelta64(hold, "m")))
                            if end <= i0 + 1:
                                continue
                            side = (1 if ret[j] > 0 else -1) * (1 if mode == "a favor" else -1)
                            stop = stop_atr * atr[j]
                            r, ex = trade(o, h, l, c, i0, side, stop, tgt * stop, end)
                            cost = sp[i0] * point / stop
                            res["escolha" if t[i0] < CHOOSE_END else "confirmação"].append(r - cost)
                            busy = ex
                        out.append((q, stop_atr, tgt, hold, mode, res))
    return out


def summ(rs):
    rs = np.array(rs, dtype=float)
    if len(rs) == 0:
        return {"n": 0, "er": None}
    return {"n": int(len(rs)), "er": float(rs.mean()), "se": float(rs.std(ddof=1) / math.sqrt(len(rs))) if len(rs) > 1 else None,
            "hit": float((rs > 0).mean())}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--lake", default=os.environ.get("LAKE_ROOT", os.path.expanduser("~/FOREX_JEV/jev-lake")))
    ap.add_argument("--from", dest="start", default="2022-01-01")
    ap.add_argument("--markets", default="crypto,metals,fx,indices,energy,stocks")
    a = ap.parse_args()
    t0 = time.time()
    pts = points(a.lake)
    agg = {}   # (mercado, config) → resultados somados dos símbolos
    for market in a.markets.split(","):
        for sym in DEFAULT_SYMBOLS[market]:
            k = load(a.lake, market, sym, a.start)
            if k is None or len(k["t"]) < 50_000 or sym not in pts:
                print(f"  {market}/{sym}: sem dados")
                continue
            for q, s, tg, hold, mode, res in study_symbol(k, pts[sym]):
                key = (market, q, s, tg, hold, mode)
                cur = agg.setdefault(key, {"escolha": [], "confirmação": []})
                cur["escolha"] += res["escolha"]
                cur["confirmação"] += res["confirmação"]
            print(f"  {market}/{sym}: ok ({time.time() - t0:.0f} s)", flush=True)
    report = []
    for (market, q, s, tg, hold, mode), res in agg.items():
        report.append({"market": market, "q": q, "stop_atr": s, "target_r": tg, "hold_min": hold, "mode": mode,
                       "escolha": summ(res["escolha"]), "confirmação": summ(res["confirmação"])})
    print("\n=== Momentum após hora grande — por mercado (escolha até 2025-06 / confirmação depois; custo = spread Exness)")
    for market in a.markets.split(","):
        rows = [r for r in report if r["market"] == market and r["escolha"]["n"] >= 30]
        if not rows:
            continue
        for mode in ("a favor", "contra"):
            sub = [r for r in rows if r["mode"] == mode]
            pos = [r for r in sub if r["escolha"]["er"] > 0]
            surv = [r for r in pos if (r["confirmação"]["er"] or -1) > 0]
            print(f"  {market:<8} {mode:<8}: {len(pos):>2}/{len(sub)} positivas na escolha, {len(surv):>2} seguem na confirmação")
        best = sorted([r for r in rows if r["mode"] == "a favor"], key=lambda r: -r["escolha"]["er"])[:3]
        for r in best:
            e, cf_ = r["escolha"], r["confirmação"]
            print(f"      top {r['q'] * 100:.0f}% · stop {r['stop_atr']} ATR · alvo {r['target_r']} · {r['hold_min'] // 60} h: "
                  f"escolha {e['er']:+.3f} R (n {e['n']}, ±{e['se'] or 0:.3f}) | confirmação "
                  f"{(cf_['er'] if cf_['er'] is not None else float('nan')):+.3f} R (n {cf_['n']}, ±{cf_.get('se') or 0:.3f})")
    out = os.path.join(a.lake, "reports", "momentum")
    os.makedirs(out, exist_ok=True)
    with open(os.path.join(out, "report.json"), "w", encoding="utf-8") as f:
        json.dump({"built_at": dt.datetime.now(dt.timezone.utc).isoformat(), "from": a.start, "choose_end": str(CHOOSE_END),
                   "rows": report}, f, indent=1)
    print(f"\nGravado em {out} · {time.time() - t0:.0f} s")


if __name__ == "__main__":
    sys.exit(main())
