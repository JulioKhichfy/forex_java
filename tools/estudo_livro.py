"""
Livro de ofertas no bitcoin (passo 12f, pedido do operador em 25/10/2026): a profundidade agregada do livro dos futuros da
Binance (bookDepth: quanto há de ordens a ±1…5% do preço, a cada ~30 s, desde 2023) prevê a direção?

Desequilíbrio a k% = (compras até −k% − vendas até +k%) / (soma), k = 1, 2, 5; na hora cheia: a última foto até o fim da
hora ("agora") e a média das fotos da hora.
  H1 livro:   desequilíbrio no topo (fundo) de q% dos últimos 30 dias → COMPRA (VENDA) — mais compradores esperando;
              controle: o contrário; base: o retorno da última hora na mesma frequência.
  H2 filtro:  as entradas do Momentum BTC separadas por "livro a favor do movimento" acima × abaixo da mediana (da escolha).
Execução nos candles M1 da Exness (BTCUSD), spread da entrada; stop em ATR(14) de 1 h. Escolha 2023-01 a 2025-06,
confirmação depois.

Uso:  python tools/estudo_livro.py [--lake …]   →  reports/livro/report.json e o resumo
"""
import argparse
import glob
import io
import json
import math
import os
import sys
import time
import zipfile

import duckdb
import numpy as np

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import estudo_momentum as M   # noqa: E402

CHOOSE_END = np.datetime64("2025-07-01T00:00")
QS = (0.02, 0.05, 0.10)
EXITS = [(s, t, h) for s in (1.0, 1.5, 2.0) for t in (1.0, 1.5, 2.0) for h in (60, 240)]


def build_cache(lake, cache):
    """Uma linha por foto do livro: profundidade acumulada de compra e de venda a 1, 2 e 5% (BTC)."""
    files = sorted(glob.glob(os.path.join(lake, "bronze", "binance_um", "book_depth", "symbol=BTCUSDT", "*.zip")))
    con = duckdb.connect()
    parts = []
    t0 = time.time()
    tmp = cache + ".csv"
    with open(tmp, "w") as out:
        out.write("ts,pct,depth\n")
        for i, z in enumerate(files):
            with zipfile.ZipFile(z) as f:
                for name in f.namelist():
                    lines = f.read(name).decode().splitlines()
                    for ln in lines[1:] if lines and not lines[0][0].isdigit() else lines:
                        p = ln.split(",")
                        if len(p) >= 3:
                            out.write(f"{p[0]},{p[1]},{p[2]}\n")
            if (i + 1) % 200 == 0:
                print(f"  livro: {i + 1}/{len(files)} arquivos ({time.time() - t0:.0f} s)", flush=True)
    con.execute(f"""
        COPY (SELECT CAST(ts AS TIMESTAMP) AS ts,
                     max(depth) FILTER (WHERE pct = -1) b1, max(depth) FILTER (WHERE pct = 1) a1,
                     max(depth) FILTER (WHERE pct = -2) b2, max(depth) FILTER (WHERE pct = 2) a2,
                     max(depth) FILTER (WHERE pct = -5) b5, max(depth) FILTER (WHERE pct = 5) a5
                FROM read_csv('{tmp.replace(os.sep, '/')}', header=true, columns={{'ts': 'VARCHAR', 'pct': 'INTEGER', 'depth': 'DOUBLE'}})
               GROUP BY 1 ORDER BY 1) TO '{cache.replace(os.sep, '/')}' (FORMAT parquet)""")
    os.remove(tmp)
    print(f"  livro → cache ({time.time() - t0:.0f} s)")


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
    cache = os.path.join(a.lake, "tmp", "binance_cache", "BTCUSDT_book.parquet")
    if not os.path.exists(cache):
        build_cache(a.lake, cache)
    bk = duckdb.connect().sql(f"SELECT * FROM '{cache.replace(os.sep, '/')}' ORDER BY ts").fetchnumpy()
    bts = bk["ts"].astype("datetime64[s]")
    imb = {k: (bk[f"b{k}"] - bk[f"a{k}"]) / (bk[f"b{k}"] + bk[f"a{k}"]) for k in (1, 2, 5)}

    k = M.load(a.lake, "crypto", "BTCUSD", "2023-01-01")
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
    end_s = (t[last] + np.timedelta64(1, "m")).astype("datetime64[s]")
    start_s = t[first].astype("datetime64[s]")
    hi_ = np.searchsorted(bts, end_s)                  # fotos antes do fim da hora
    lo_ = np.searchsorted(bts, start_s)
    print(f"Exness BTCUSD {n:,} minutos · {len(last):,} horas · {len(bts):,} fotos do livro ({time.time() - t0:.0f} s)")
    feats = {}
    for kk, x in imb.items():
        cs = np.r_[0, np.cumsum(np.nan_to_num(x))]
        cnt = hi_ - lo_
        now = np.where((hi_ > 0) & (end_s - bts[np.maximum(hi_ - 1, 0)] <= np.timedelta64(120, "s")), x[np.maximum(hi_ - 1, 0)], np.nan)
        avg = np.where(cnt >= 60, (cs[hi_] - cs[lo_]) / np.maximum(cnt, 1), np.nan)
        feats[(kk, "agora")] = now
        feats[(kk, "média da hora")] = avg

    def run_rule(sig, follow, ex):
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
        s = np.zeros(len(x))
        for j in range(720, len(x)):
            win = x[j - 720:j]
            win = win[~np.isnan(win)]
            if len(win) < 500 or np.isnan(x[j]):
                continue
            up, dn = np.quantile(win, [1 - q, q])
            s[j] = 1 if x[j] >= up else -1 if x[j] <= dn else 0
        return s

    report = []
    print("\n=== H1 livro (mais compra no livro → COMPRA) × base preço — positivas na escolha / seguem na confirmação")
    for q in QS:
        sr = extremes(hret, q)
        for name, x in list(feats.items()) + [(("—", "retorno da hora"), hret)]:
            sig = extremes(x, q) if name[1] != "retorno da hora" else sr
            for follow in (True, False):
                rows = [{"k": name[0], "kind": name[1], "q": q, "mode": "a favor" if follow else "contra",
                         "stop_atr": ex[0], "target_r": ex[1], "hold": ex[2], **run_rule(sig, follow, ex)} for ex in EXITS]
                report += rows
                pos = [r for r in rows if r["escolha"]["n"] >= 30 and r["escolha"]["er"] > 0]
                surv = [r for r in pos if (r["confirmação"]["er"] or -1) > 0]
                best = max(rows, key=lambda r: r["escolha"]["er"] if r["escolha"]["er"] is not None else -9)
                e, cf = best["escolha"], best["confirmação"]
                lbl = f"{name[0]}% {name[1]}" if name[0] != "—" else "preço (retorno 1 h)"
                print(f"  topo {q * 100:>2.0f}% · {lbl:<20} {('a favor' if follow else 'contra'):<7}: {len(pos):>2}/{len(rows)} "
                      f"positivas, {len(surv):>2} seguem · melhor {e['er']:+.3f} (n {e['n']}) | "
                      f"{(cf['er'] if cf['er'] is not None else float('nan')):+.3f} (n {cf['n']})")

    print("\n=== H2 Momentum BTC (topo 2%, stop 1,5 ATR, alvo 1,5, 24 h) separado pelo livro a favor do movimento")
    big = np.zeros(len(hret))
    for j in range(720, len(hret)):
        thr = np.quantile(np.abs(hret[j - 720:j]), 0.98)
        if abs(hret[j]) >= thr and hret[j] != 0:
            big[j] = np.sign(hret[j])
    in_choose = t[last] < CHOOSE_END
    r_all = run_rule(big, True, (1.5, 1.5, 1440))
    print(f"  todas: escolha {r_all['escolha']['er']:+.3f} (n {r_all['escolha']['n']}) | confirmação "
          f"{r_all['confirmação']['er']:+.3f} (n {r_all['confirmação']['n']})")
    for key, x in feats.items():
        agree = big * x
        med = np.nanmedian(agree[(big != 0) & in_choose & ~np.isnan(agree)])
        for name, mask in (("a favor (≥ mediana)", (big != 0) & (agree >= med)), ("contra (< mediana)", (big != 0) & (agree < med))):
            r = run_rule(np.where(mask, big, 0), True, (1.5, 1.5, 1440))
            report.append({"h2": f"{key[0]}% {key[1]} {name}", **r})
            e, cf = r["escolha"], r["confirmação"]
            print(f"  livro {key[0]}% {key[1]:<14} {name:<20}: escolha {e['er']:+.3f} ±{e['se'] or 0:.3f} (n {e['n']}) | "
                  f"confirmação {(cf['er'] if cf['er'] is not None else float('nan')):+.3f} ±{cf['se'] or 0:.3f} (n {cf['n']})")
    out = os.path.join(a.lake, "reports", "livro")
    os.makedirs(out, exist_ok=True)
    with open(os.path.join(out, "report.json"), "w", encoding="utf-8") as f:
        json.dump(report, f, indent=1)
    print(f"\nGravado em {out} · {time.time() - t0:.0f} s")


if __name__ == "__main__":
    sys.exit(main())
