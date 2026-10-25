"""
Fluxo agressor no bitcoin (passo 12e, pedido do operador em 25/10/2026): a compra e a venda A MERCADO (taker) dos futuros da
Binance, minuto a minuto (bronze/binance_um, cache de tools/estudo_liquidacoes.py), prevê a direção?

Desequilíbrio da janela w = (compra agressiva − venda agressiva) / volume, w = 15, 60 e 240 min, no fim de cada hora.
  H1 fluxo:    desequilíbrio no topo (fundo) de q% dos últimos 30 dias → COMPRA (VENDA); controle: o contrário.
  Base preço:  o mesmo com o RETORNO da janela no lugar do fluxo (fluxo e retorno andam juntos: o fluxo só vale se for
               melhor que o preço).
  H3 filtro:   as entradas do Momentum BTC (hora no topo de 2% de |retorno|) separadas por "fluxo da hora a favor" ×
               "contra" (fluxo concorda com o movimento além da mediana dessas horas?).
Execução nos candles M1 da Exness (BTCUSD, silver), spread da entrada; stop em ATR(14) de 1 h, alvo, saída por tempo.
Escolha até 2025-06, confirmação depois.

Uso:  python tools/estudo_fluxo.py [--lake …]   →  reports/fluxo/report.json e o resumo
"""
import argparse
import json
import math
import os
import sys
import time

import duckdb
import numpy as np

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import estudo_momentum as M   # noqa: E402  (mesma simulação de operação e os mesmos candles da Exness)

CHOOSE_END = np.datetime64("2025-07-01T00:00")
WINDOWS = (15, 60, 240)
QS = (0.02, 0.05, 0.10)
EXITS = [(s, t, h) for s in (1.0, 1.5, 2.0) for t in (1.0, 1.5, 2.0) for h in (60, 240)]


def summ(rs):
    rs = np.asarray(rs, dtype=float)
    if len(rs) == 0:
        return {"n": 0, "er": None, "se": None}
    return {"n": int(len(rs)), "er": float(rs.mean()), "se": float(rs.std(ddof=1) / math.sqrt(len(rs))) if len(rs) > 1 else None}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--lake", default=os.environ.get("LAKE_ROOT", os.path.expanduser("~/FOREX_JEV/jev-lake")))
    a = ap.parse_args()
    t0 = time.time()
    cache = os.path.join(a.lake, "tmp", "binance_cache", "BTCUSDT_klines.parquet").replace(os.sep, "/")
    if not os.path.exists(cache):
        sys.exit("Sem o cache dos candles da Binance: rode antes tools/estudo_liquidacoes.py")
    b = duckdb.connect().sql(f"SELECT t, c, v, tbv FROM '{cache}' ORDER BY t").fetchnumpy()
    bt = b["t"].astype("datetime64[m]")
    cum_v = np.r_[0, np.cumsum(b["v"])]
    cum_d = np.r_[0, np.cumsum(2 * b["tbv"] - b["v"])]    # compra agressiva − venda agressiva
    bc = b["c"]

    k = M.load(a.lake, "crypto", "BTCUSD", "2022-01-01")
    pt = M.points(a.lake)["BTCUSD"]
    t = k["t"].astype("datetime64[m]")
    o, h, l, c, sp = k["o"], k["h"], k["l"], k["c"], k["sp"].astype(float)
    n = len(t)
    hr = t.astype("datetime64[h]")
    last = np.flatnonzero(np.r_[hr[1:] != hr[:-1], True])
    first = np.r_[0, last[:-1] + 1]
    hh, hl, hc, ho = np.maximum.reduceat(h, first), np.minimum.reduceat(l, first), c[last], o[first]
    prev = np.r_[hc[0], hc[:-1]]
    atr = np.convolve(np.maximum(hh - hl, np.maximum(abs(hh - prev), abs(hl - prev))), np.ones(14) / 14)[:len(hh)]
    hret = np.log(hc / ho)
    end_t = t[last] + np.timedelta64(1, "m")       # fim da hora (exclusivo)
    bi = np.searchsorted(bt, end_t)                # minutos da Binance antes do fim da hora
    print(f"Exness BTCUSD {n:,} minutos · {len(last):,} horas · Binance {len(bt):,} minutos ({time.time() - t0:.0f} s)")

    feats = {}
    for w in WINDOWS:
        lo = np.searchsorted(bt, end_t - np.timedelta64(w, "m"))
        vol = cum_v[bi] - cum_v[lo]
        flow = np.where(vol > 0, (cum_d[bi] - cum_d[lo]) / np.where(vol > 0, vol, 1), np.nan)
        # retorno da mesma janela (Binance), para a comparação "o fluxo é melhor que o preço?"
        ok = (bi > 0) & (lo > 0) & (bi - lo >= w * 0.8)
        ret = np.where(ok, np.log(bc[np.maximum(bi - 1, 0)] / bc[np.maximum(lo - 1, 0)]), np.nan)
        feats[w] = (np.where(ok, flow, np.nan), ret)

    def run_rule(sig, follow, ex):
        """sig[j] = +1/−1/0 por hora; follow=False inverte. Uma operação por vez."""
        stop_atr, tgt, hold = ex
        res = {"escolha": [], "confirmação": []}
        busy = -1
        for j in np.flatnonzero(sig != 0):
            if atr[j] <= 0:
                continue
            i0 = last[j] + 1
            if i0 <= busy or i0 >= n:
                continue
            end = int(np.searchsorted(t, t[i0] + np.timedelta64(hold, "m")))
            if end <= i0 + 1:
                continue
            side = int(sig[j]) * (1 if follow else -1)
            stop = stop_atr * atr[j]
            r, exi = M.trade(o, h, l, c, i0, side, stop, tgt * stop, end)
            res["escolha" if t[i0] < CHOOSE_END else "confirmação"].append(r - sp[i0] * pt / stop)
            busy = exi
        return {p: summ(v) for p, v in res.items()}

    def extremes(x, q):
        """+1 no topo de q dos últimos 30 dias (720 horas), −1 no fundo; point-in-time."""
        s = np.zeros(len(x))
        for j in range(720, len(x)):
            win = x[j - 720:j]
            win = win[~np.isnan(win)]
            if len(win) < 500 or np.isnan(x[j]):
                continue
            hi, lo_ = np.quantile(win, [1 - q, q])
            s[j] = 1 if x[j] >= hi else -1 if x[j] <= lo_ else 0
        return s

    report = []
    print("\n=== H1 fluxo × base preço (mesma frequência) — quantas configurações positivas na escolha e quantas seguem")
    for w in WINDOWS:
        flow, ret = feats[w]
        for q in QS:
            sf, sr = extremes(flow, q), extremes(ret, q)
            for name, sig in (("fluxo", sf), ("preço", sr)):
                for follow in (True, False):
                    rows = [{"w": w, "q": q, "signal": name, "mode": "a favor" if follow else "contra",
                             "stop_atr": ex[0], "target_r": ex[1], "hold": ex[2], **run_rule(sig, follow, ex)} for ex in EXITS]
                    report += rows
                    pos = [r for r in rows if r["escolha"]["n"] >= 30 and r["escolha"]["er"] > 0]
                    surv = [r for r in pos if (r["confirmação"]["er"] or -1) > 0]
                    best = max(rows, key=lambda r: r["escolha"]["er"] if r["escolha"]["er"] is not None else -9)
                    e, cf = best["escolha"], best["confirmação"]
                    print(f"  {w:>3} min · topo {q * 100:>2.0f}% · {name:<5} {('a favor' if follow else 'contra'):<7}: "
                          f"{len(pos):>2}/{len(rows)} positivas, {len(surv):>2} seguem · melhor {e['er']:+.3f} (n {e['n']}) | "
                          f"{(cf['er'] if cf['er'] is not None else float('nan')):+.3f} (n {cf['n']})")
    # H3: o momentum filtrado pelo fluxo da hora grande
    print("\n=== H3 Momentum BTC (topo 2% de |retorno da hora|, stop 1,5 ATR, alvo 1,5, 24 h) separado pelo fluxo da hora")
    flow60 = feats[60][0]
    big = np.zeros(len(hret))
    for j in range(720, len(hret)):
        thr = np.quantile(np.abs(hret[j - 720:j]), 0.98)
        if abs(hret[j]) >= thr and hret[j] != 0:
            big[j] = np.sign(hret[j])
    agree = big * flow60          # > 0: o fluxo da hora concorda com o movimento
    in_choose = t[last] < CHOOSE_END
    # o corte vem SÓ do período de escolha (a mediana de todas as horas usaria a confirmação)
    med = np.nanmedian(agree[(big != 0) & in_choose])
    print(f"  (mediana do fluxo a favor nas horas grandes da escolha: {med:+.3f})")
    for name, mask in (("todas", big != 0), ("fluxo a favor (acima da mediana)", (big != 0) & (agree >= med)),
                       ("fluxo fraco/contra (abaixo)", (big != 0) & (agree < med)),
                       ("fluxo no mesmo sentido (> 0)", (big != 0) & (agree > 0)),
                       ("fluxo no sentido oposto (≤ 0)", (big != 0) & (agree <= 0))):
        r = run_rule(np.where(mask, big, 0), True, (1.5, 1.5, 1440))
        report.append({"h3": name, **r})
        e, cf = r["escolha"], r["confirmação"]
        print(f"  {name:<34}: escolha {e['er']:+.3f} ±{e['se'] or 0:.3f} (n {e['n']}) | confirmação "
              f"{cf['er']:+.3f} ±{cf['se'] or 0:.3f} (n {cf['n']})")
    out = os.path.join(a.lake, "reports", "fluxo")
    os.makedirs(out, exist_ok=True)
    with open(os.path.join(out, "report.json"), "w", encoding="utf-8") as f:
        json.dump(report, f, indent=1)
    print(f"\nGravado em {out} · {time.time() - t0:.0f} s")


if __name__ == "__main__":
    sys.exit(main())
