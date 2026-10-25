"""
Estudo do mapa de liquidações no bitcoin (passo 12, pedido do operador em 25/10/2026).

O mapa NÃO é dado observado: é o mesmo modelo dos sites de "liquidation heatmap". Quando o open interest (OI) dos futuros
perpétuos da Binance sobe num intervalo de 5 min, entraram posições novas ao preço médio do intervalo; a parte comprada
(fração do volume agressor comprador) seria liquidada abaixo, a vendida acima, nas alavancagens supostas (25×, 50×, 100×,
as do print do operador). Cada nível some quando o preço passa por ele (liquidado) e encolhe quando o OI cai (posições
fechadas). Point-in-time: o mapa no instante t usa só OI e candles até t.

Hipóteses (escolha até 2025-06, confirmação depois — nunca escolher olhando a confirmação):
  H1 ímã:  o preço chega antes ao lado com mais liquidação estimada (acima × abaixo)?
  H2 varrer e voltar: depois de uma hora em que o preço liquidou muito de um lado, operar CONTRA o movimento dá E[R] > 0
           (e a favor, a "cascata")? Comparado com o mesmo após horas de movimento igualmente grande sem a condição.

Custos: o spread do BTCUSD da Exness (onde o operador opera) no minuto da entrada, do silver do lake.

Uso:
    python tools/estudo_liquidacoes.py [--lake …] [--symbol BTCUSDT] [--png-days 30]
Saída: reports/liquidacoes/<símbolo>/report.json, heatmap.png e o resumo no terminal.
"""
import argparse
import datetime as dt
import glob
import io
import json
import math
import os
import struct
import sys
import time
import zipfile
import zlib

import duckdb
import numpy as np

LEVERAGES = (25, 50, 100)          # as do print do operador (10× desmarcado)
MMR = 0.004                         # margem de manutenção do BTCUSDT na faixa pequena (0,4%)
P0, STEP = math.log(5_000.0), 0.001  # grade de preço em log: 0,1% por faixa
NBINS = int((math.log(400_000.0) - P0) / STEP) + 1
CHOOSE_END = np.datetime64("2025-07-01T00:00")


# ---------------------------------------------------------------------------------------------- leitura do bronze

def read_zips(pattern, kind):
    """Lê os CSVs dos zips (alguns meses antigos da Binance vêm sem cabeçalho)."""
    rows = []
    for z in sorted(glob.glob(pattern)):
        with zipfile.ZipFile(z) as f:
            for name in f.namelist():
                text = f.read(name).decode()
                lines = text.splitlines()
                if lines and not lines[0][0].isdigit():
                    lines = lines[1:]
                rows.append("\n".join(lines))
    return "\n".join(r for r in rows if r)


def load(lake, symbol, cache):
    os.makedirs(cache, exist_ok=True)
    kp, mp = os.path.join(cache, f"{symbol}_klines.parquet"), os.path.join(cache, f"{symbol}_metrics.parquet")
    con = duckdb.connect()
    base = os.path.join(lake, "bronze", "binance_um")
    if not os.path.exists(kp):
        t0 = time.time()
        csv = read_zips(os.path.join(base, "klines_1m", f"symbol={symbol}", "*.zip"), "klines")
        tmp = os.path.join(cache, f"{symbol}_klines.csv")
        open(tmp, "w").write(csv)
        con.execute(f"""
            COPY (SELECT DISTINCT ON (t) make_timestamp(CAST(column00 AS BIGINT) * 1000) AS t,
                         CAST(column01 AS DOUBLE) o, CAST(column02 AS DOUBLE) h, CAST(column03 AS DOUBLE) l,
                         CAST(column04 AS DOUBLE) c, CAST(column05 AS DOUBLE) v, CAST(column07 AS DOUBLE) qv,
                         CAST(column09 AS DOUBLE) tbv
                    FROM read_csv('{tmp.replace(os.sep, '/')}', header=false, all_varchar=true)
                   ORDER BY t) TO '{kp.replace(os.sep, '/')}' (FORMAT parquet)""")
        os.remove(tmp)
        print(f"  klines → cache ({time.time() - t0:.0f} s)")
    if not os.path.exists(mp):
        t0 = time.time()
        csv = read_zips(os.path.join(base, "metrics", f"symbol={symbol}", "*.zip"), "metrics")
        tmp = os.path.join(cache, f"{symbol}_metrics.csv")
        open(tmp, "w").write(csv)
        con.execute(f"""
            COPY (SELECT DISTINCT ON (t) CAST(column0 AS TIMESTAMP) AS t, CAST(column2 AS DOUBLE) AS oi
                    FROM read_csv('{tmp.replace(os.sep, '/')}', header=false, all_varchar=true)
                   WHERE TRY_CAST(column2 AS DOUBLE) > 0   -- a Binance tem carimbos com OI 0 (dado faltando, não posições zeradas)
                   ORDER BY t) TO '{mp.replace(os.sep, '/')}' (FORMAT parquet)""")
        os.remove(tmp)
        print(f"  metrics → cache ({time.time() - t0:.0f} s)")
    k = con.sql(f"SELECT * FROM '{kp.replace(os.sep, '/')}' ORDER BY t").fetchnumpy()
    m = con.sql(f"SELECT * FROM '{mp.replace(os.sep, '/')}' ORDER BY t").fetchnumpy()
    return k, m


def exness_spread(lake):
    """Spread do BTCUSD na Exness por minuto (fração do preço); fora do silver, a mediana do ano."""
    files = [f.replace(os.sep, "/") for f in glob.glob(f"{lake}/silver/candles_m1/market=crypto/symbol=BTCUSD/**/*.parquet",
                                                       recursive=True)]
    if not files:
        return None, {}
    con = duckdb.connect()
    s = con.sql(f"""SELECT time_utc AS t, spread_points * 0.01 / close AS f FROM read_parquet({files!r})
                     WHERE close > 0 ORDER BY t""").fetchnumpy()
    med = con.sql(f"""SELECT year(time_utc) y, median(spread_points * 0.01 / close) FROM read_parquet({files!r})
                       WHERE close > 0 GROUP BY 1""").fetchall()
    return s, {int(y): float(v) for y, v in med}


# ---------------------------------------------------------------------------------------------- o mapa

def idx(p):
    return np.clip(((np.log(p) - P0) / STEP).astype(np.int64), 0, NBINS - 1)


def build_map(k, m, snap_every=60, png_from=None):
    """
    Simula o mapa minuto a minuto. Devolve, por minuto, quanto foi liquidado de cada lado (em BTC estimado) e, a cada hora
    cheia, as features do mapa naquele instante (antes de olhar o futuro).
    """
    t = k["t"].astype("datetime64[m]")
    h, l, c, v, qv, tbv = k["h"], k["l"], k["c"], k["v"], k["qv"], k["tbv"]
    n = len(t)
    mt = m["t"].astype("datetime64[m]")
    oi = m["oi"]
    one = np.timedelta64(1, "m")
    # o último carimbo de OI até o FIM de cada barra (o de 00:05 fecha as barras 00:00–00:04)
    pos = np.searchsorted(mt, t + one, side="right") - 1
    hour_end = (t + one).astype("datetime64[h]") == (t + one)
    L = np.zeros(NBINS)    # liquidação de COMPRADOS (abaixo do preço)
    S = np.zeros(NBINS)    # liquidação de VENDIDOS (acima do preço)
    lmax, smin = -1, NBINS
    liqL, liqS = np.zeros(n), np.zeros(n)
    snaps = []
    png = []
    lev = np.array(LEVERAGES, dtype=float)
    w = 1.0 / len(lev)
    hi_i, lo_i = idx(h), idx(l)
    last_oi_pos = -1
    acc_qv = acc_v = acc_tb = 0.0
    for i in range(n):
        # 1) o preço do minuto liquida o que estiver no caminho
        if hi_i[i] >= smin:
            j = hi_i[i] + 1
            liqS[i] = S[smin:j].sum()
            S[smin:j] = 0
            nz = np.flatnonzero(S[j:])
            smin = j + nz[0] if len(nz) else NBINS
        if lo_i[i] <= lmax:
            j = lo_i[i]
            liqL[i] = L[j:lmax + 1].sum()
            L[j:lmax + 1] = 0
            nz = np.flatnonzero(L[:j])
            lmax = nz[-1] if len(nz) else -1
        acc_qv += qv[i]
        acc_v += v[i]
        acc_tb += tbv[i]
        # 2) carimbo novo de OI: posições abertas (ou fechadas) desde o anterior, ao preço médio do intervalo
        p = pos[i]
        if p >= 0 and p != last_oi_pos:
            d_oi = oi[p] - oi[last_oi_pos] if last_oi_pos >= 0 else 0.0
            if d_oi > 0 and acc_v > 0:
                vwap = acc_qv / acc_v
                b = acc_tb / acc_v
                long_px = vwap * (1 - 1 / lev + MMR)
                short_px = vwap * (1 + 1 / lev - MMR)
                li, si = idx(long_px), idx(short_px)
                np.add.at(L, li, d_oi * b * w)
                np.add.at(S, si, d_oi * (1 - b) * w)
                lmax = max(lmax, int(li.max()))
                smin = min(smin, int(si.min()))
            elif d_oi < 0 and oi[last_oi_pos] > 0:
                f = max(0.0, 1 + d_oi / oi[last_oi_pos])   # posições fechadas: proporcional
                L *= f
                S *= f
            last_oi_pos = p
            acc_qv = acc_v = acc_tb = 0.0
        # 3) foto do mapa na hora cheia (fim do minuto :59)
        if hour_end[i]:
            P = c[i]
            ip = int(idx(np.array([P]))[0])
            row = {"i": i, "P": P}
            for ww in (0.02, 0.05):
                k_up = int(idx(np.array([P * (1 + ww)]))[0])
                k_dn = int(idx(np.array([P * (1 - ww)]))[0])
                up, dn = S[ip:k_up + 1], L[k_dn:ip + 1]
                row[f"up{int(ww * 100)}"] = float(up.sum())
                row[f"dn{int(ww * 100)}"] = float(dn.sum())
                row[f"pkup{int(ww * 100)}"] = float(up.max()) if len(up) else 0.0
                row[f"pkdn{int(ww * 100)}"] = float(dn.max()) if len(dn) else 0.0
            snaps.append(row)
            if png_from is not None and t[i] >= png_from:
                png.append((t[i], P, L[ip - 120:ip + 121].copy() if ip >= 120 else None, S[ip - 120:ip + 121].copy()
                            if ip >= 120 else None, ip))
    return liqL, liqS, snaps, png


# ---------------------------------------------------------------------------------------------- resultados

def first_touch(h, l, i0, up, dn, horizon):
    """1 = tocou 'up' antes, 0 = 'dn' antes, None = nenhum ou os dois no mesmo minuto."""
    hh, ll = h[i0:i0 + horizon], l[i0:i0 + horizon]
    a = np.flatnonzero(hh >= up)
    b = np.flatnonzero(ll <= dn)
    ia = a[0] if len(a) else None
    ib = b[0] if len(b) else None
    if ia is None and ib is None:
        return None
    if ia is not None and ib is not None and ia == ib:
        return None
    if ib is None or (ia is not None and ia < ib):
        return 1
    return 0


def trade(o, h, l, c, i0, side, stop, target, horizon):
    """
    Entrada na abertura do minuto i0; stop/alvo em preço; stop e alvo no mesmo minuto = stop (pessimista).
    Retorna (R bruto, minuto da saída).
    """
    e = o[i0]
    end = min(len(o), i0 + horizon)
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


def summarize(rs):
    rs = np.array(rs, dtype=float)
    if len(rs) == 0:
        return {"n": 0}
    return {"n": int(len(rs)), "er": float(rs.mean()), "hit": float((rs > 0).mean()),
            "se": float(rs.std(ddof=1) / math.sqrt(len(rs))) if len(rs) > 1 else None, "sum": float(rs.sum())}


# ---------------------------------------------------------------------------------------------- PNG sem dependências

def write_png(path, rgb):
    hgt, wid, _ = rgb.shape
    raw = b"".join(b"\x00" + rgb[y].astype(np.uint8).tobytes() for y in range(hgt))

    def chunk(tag, data):
        return struct.pack(">I", len(data)) + tag + data + struct.pack(">I", zlib.crc32(tag + data) & 0xffffffff)

    with open(path, "wb") as f:
        f.write(b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", wid, hgt, 8, 2, 0, 0, 0))
                + chunk(b"IDAT", zlib.compress(raw, 9)) + chunk(b"IEND", b""))


def heatmap_png(path, png, k):
    """Uma coluna por hora, ±12% do preço em linhas de 0,1%; brilho = liquidação estimada; linha branca = preço."""
    cols = [x for x in png if x[2] is not None]
    if not cols:
        return
    lo = min(x[4] for x in cols) - 120
    hi = max(x[4] for x in cols) + 120
    hgt, wid = hi - lo + 1, len(cols)
    grid = np.zeros((hgt, wid))
    price_row = np.zeros(wid, dtype=int)
    for x, (_, P, Lw, Sw, ip) in enumerate(cols):
        a = ip - 120 - lo
        grid[a:a + 241, x] = Lw + Sw
        price_row[x] = ip - lo
    v = np.log1p(grid / (np.percentile(grid[grid > 0], 50) if (grid > 0).any() else 1))
    v = v / (v.max() or 1)
    # escala: preto → azul → verde → amarelo → vermelho
    stops = np.array([[10, 12, 20], [20, 60, 140], [30, 170, 90], [240, 220, 40], [230, 40, 40]], dtype=float)
    pos = v * (len(stops) - 1)
    i0 = np.clip(pos.astype(int), 0, len(stops) - 2)
    fr = (pos - i0)[..., None]
    rgb = stops[i0] * (1 - fr) + stops[i0 + 1] * fr
    for x in range(wid):
        rgb[price_row[x], x] = (255, 255, 255)
    rgb = rgb[::-1]   # preço alto em cima
    rgb = np.repeat(np.repeat(rgb, 2, axis=0), 3, axis=1)
    write_png(path, rgb)


# ---------------------------------------------------------------------------------------------- estudo

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--lake", default=os.environ.get("LAKE_ROOT", os.path.expanduser("~/FOREX_JEV/jev-lake")))
    ap.add_argument("--symbol", default="BTCUSDT")
    ap.add_argument("--png-days", type=int, default=30)
    a = ap.parse_args()
    t_start = time.time()
    k, m = load(a.lake, a.symbol, os.path.join(a.lake, "tmp", "binance_cache"))
    n = len(k["t"])
    print(f"{a.symbol}: {n:,} minutos ({k['t'][0]} → {k['t'][-1]}), {len(m['t']):,} carimbos de OI")
    png_from = (k["t"][-1].astype("datetime64[m]") - np.timedelta64(a.png_days, "D"))
    t0 = time.time()
    liqL, liqS, snaps, png = build_map(k, m, png_from=png_from)
    print(f"mapa simulado em {time.time() - t0:.0f} s; {len(snaps):,} horas")
    o, h, l, c = k["o"], k["h"], k["l"], k["c"]
    t = k["t"].astype("datetime64[m]")
    sp_t, sp_med = exness_spread(a.lake)
    sp_times = sp_t["t"].astype("datetime64[m]") if sp_t is not None else None   # converte uma vez só

    def spread_at(i):
        if sp_times is not None:
            j = np.searchsorted(sp_times, t[i])
            if 0 <= j < len(sp_times) and sp_times[j] == t[i]:
                return float(sp_t["f"][j])
        return sp_med.get(int(str(t[i])[:4]), 0.0003)

    out = {"symbol": a.symbol, "built_at": dt.datetime.now(dt.timezone.utc).isoformat(), "leverages": LEVERAGES, "mmr": MMR,
           "from": str(t[0]), "to": str(t[-1]), "choose_end": str(CHOOSE_END)}

    # hora a hora: ATR(14) de 1 h para os stops
    hours = np.array([s["i"] for s in snaps])
    hc = c[hours]
    hh = np.maximum.reduceat(h, np.r_[0, hours[:-1] + 1])[:len(hours)]
    hl = np.minimum.reduceat(l, np.r_[0, hours[:-1] + 1])[:len(hours)]
    tr = np.maximum(hh - hl, np.maximum(abs(hh - np.r_[hc[0], hc[:-1]]), abs(hl - np.r_[hc[0], hc[:-1]])))
    atr = np.convolve(tr, np.ones(14) / 14, mode="full")[:len(tr)]
    period = np.where(t[hours] < CHOOSE_END, "escolha", "confirmação")

    # ------------------------------------------------------------------ H1: ímã
    print("\n=== H1 ímã: quem toca primeiro ±d em 24 h, por desequilíbrio acima × abaixo (só horas a cada 24, sem sobrepor)")
    h1 = []
    for ww in (2, 5):
        up = np.array([s[f"up{ww}"] for s in snaps])
        dn = np.array([s[f"dn{ww}"] for s in snaps])
        imb = (up - dn) / np.where(up + dn > 0, up + dn, np.nan)
        for d in (0.005, 0.01, 0.02):
            res = {}
            for per in ("escolha", "confirmação"):
                sel = np.flatnonzero((period == per) & ~np.isnan(imb))
                sel = sel[::24]   # amostras independentes (horizonte de 24 h)
                ys, xs, r24s = [], [], []
                for j in sel:
                    i0 = hours[j] + 1
                    if i0 + 1440 >= n or j < 24:
                        continue
                    y = first_touch(h, l, i0, hc[j] * (1 + d), hc[j] * (1 - d), 1440)
                    if y is not None:
                        ys.append(y)
                        xs.append(imb[j])
                        r24s.append(math.log(hc[j] / hc[j - 24]))
                ys, xs = np.array(ys), np.array(xs)
                if len(ys) < 50:
                    continue
                q = np.quantile(xs, [0.2, 0.8])
                top, bot = ys[xs >= q[1]], ys[xs <= q[0]]
                # ímã: mais liquidação acima (imb alto) → sobe primeiro mais vezes
                diff = top.mean() - bot.mean()
                se = math.sqrt(top.var() / len(top) + bot.var() / len(bot))
                # controle: o desequilíbrio é só "o preço caiu" (os comprados abaixo já foram varridos)? Regressão linear
                # de "subiu primeiro" no desequilíbrio E no retorno das 24 h anteriores; z do desequilíbrio já descontado
                rs24 = np.array(r24s)
                X = np.column_stack([np.ones(len(xs)), (xs - xs.mean()) / (xs.std() or 1),
                                     (rs24 - rs24.mean()) / (rs24.std() or 1)])
                beta, *_ = np.linalg.lstsq(X, ys, rcond=None)
                resid = ys - X @ beta
                cov = np.linalg.inv(X.T @ X) * (resid @ resid) / (len(ys) - 3)
                z_ctrl = float(beta[1] / math.sqrt(cov[1, 1]))
                z_ret = float(beta[2] / math.sqrt(cov[2, 2]))
                corr = float(np.corrcoef(xs, rs24)[0, 1])
                res[per] = {"n": int(len(ys)), "base_up": float(ys.mean()), "up_if_more_above": float(top.mean()),
                            "up_if_more_below": float(bot.mean()), "diff": float(diff), "z": float(diff / se) if se else None,
                            "z_controlled": z_ctrl, "z_past_return": z_ret, "corr_imb_ret24": corr}
            if res:
                h1.append({"window_pct": ww, "d": d, **res})
                e, cf_ = res.get("escolha", {}), res.get("confirmação", {})
                print(f"  janela ±{ww}% · alvo ±{d * 100:.1f}%: escolha n={e.get('n')} sobe {e.get('up_if_more_above', 0):.2f} "
                      f"(mais acima) × {e.get('up_if_more_below', 0):.2f} (mais abaixo), z={e.get('z') or 0:+.1f}, "
                      f"descontado o retorno de 24 h z={e.get('z_controlled', 0):+.1f} (corr {e.get('corr_imb_ret24', 0):+.2f}) | "
                      f"confirmação n={cf_.get('n')} {cf_.get('up_if_more_above', 0):.2f} × {cf_.get('up_if_more_below', 0):.2f}, "
                      f"z={cf_.get('z') or 0:+.1f}, descontado z={cf_.get('z_controlled', 0):+.1f}")
    out["h1"] = h1

    # ------------------------------------------------------------------ H2: varrer e voltar (ou cascata)
    print("\n=== H2: depois de uma hora que liquidou muito de um lado — contra (voltar) × a favor (cascata); E[R] com o spread")
    hour_liqS = np.add.reduceat(liqS, np.r_[0, hours[:-1] + 1])[:len(hours)]
    hour_liqL = np.add.reduceat(liqL, np.r_[0, hours[:-1] + 1])[:len(hours)]
    tot = hour_liqS + hour_liqL
    ret = np.r_[0, np.diff(np.log(hc))]
    trail = np.array([tot[max(0, j - 720):j].mean() if j > 24 else np.nan for j in range(len(tot))])   # 30 dias, até ali
    swept_up = hour_liqS > hour_liqL   # liquidou vendidos = o preço subiu varrendo o lado de cima
    h2 = []
    for kmult in (3, 5, 10):
        # eventos (até a hora j, nada depois): a hora liquidou ≥ k × a média dos 30 dias anteriores
        ev = [j for j in range(25, len(hours) - 1) if trail[j] > 0 and atr[j] > 0 and tot[j] >= kmult * trail[j]]
        # base: horas com movimento tão grande quanto (|retorno| no mesmo quantil que a frequência dos eventos), sem a
        # condição de liquidação — separa "o mapa ajuda" de "só reverter/seguir movimento grande"
        base = []
        for j in range(745, len(hours) - 1):
            if atr[j] <= 0:
                continue
            win = slice(j - 720, j)
            frac = (tot[win] >= kmult * trail[j]).mean() if trail[j] > 0 else 0
            if frac > 0 and abs(ret[j]) >= np.quantile(np.abs(ret[win]), 1 - frac):
                base.append(j)
        for stop_atr in (1.0, 1.5, 2.0):
            for tgt in (1.0, 1.5, 2.0):
                for mode in ("contra", "a favor", "contra (base: só movimento)", "a favor (base: só movimento)"):
                    is_base = "base" in mode
                    against = mode.startswith("contra")
                    res = {}
                    for per in ("escolha", "confirmação"):
                        rs, busy_until = [], -1
                        for j in (base if is_base else ev):
                            if period[j] != per:
                                continue
                            i0 = hours[j] + 1
                            if i0 <= busy_until or i0 + 1440 >= n:
                                continue   # uma operação de cada vez
                            up = ret[j] > 0 if is_base else swept_up[j]
                            side = (-1 if up else 1) * (1 if against else -1)
                            stop = stop_atr * atr[j]
                            r, exit_i = trade(o, h, l, c, i0, side, stop, tgt * stop, 1440)
                            rs.append(r - spread_at(i0) * o[i0] / stop)
                            busy_until = exit_i
                        res[per] = summarize(rs)
                    h2.append({"k": kmult, "stop_atr": stop_atr, "target_r": tgt, "mode": mode, **res})
    out["h2"] = h2
    ok = [x for x in h2 if x["escolha"].get("n", 0) >= 30]
    ok.sort(key=lambda x: -x["escolha"]["er"])
    print("  as 10 melhores na escolha (k = liquidação da hora × média de 30 dias):")
    for x in ok[:10]:
        e, cf_ = x["escolha"], x["confirmação"]
        print(f"   k={x['k']:>2} stop {x['stop_atr']} ATR alvo {x['target_r']} {x['mode']:<28} escolha {e['er']:+.3f} R "
              f"(n {e['n']}, ±{e['se'] or 0:.3f}) | confirmação {cf_.get('er', float('nan')):+.3f} R (n {cf_.get('n', 0)})")
    for mode in ("contra", "a favor", "contra (base: só movimento)", "a favor (base: só movimento)"):
        sub = [x for x in ok if x["mode"] == mode]
        if sub:
            pos = sum(1 for x in sub if x["escolha"]["er"] > 0)
            surv = sum(1 for x in sub if x["escolha"]["er"] > 0 and x["confirmação"].get("er", -1) > 0)
            print(f"  {mode:<28}: {pos}/{len(sub)} positivas na escolha; {surv} seguem positivas na confirmação")

    rep = os.path.join(a.lake, "reports", "liquidacoes", a.symbol)
    os.makedirs(rep, exist_ok=True)
    with open(os.path.join(rep, "report.json"), "w", encoding="utf-8") as f:
        json.dump(out, f, indent=1, default=str)
    heatmap_png(os.path.join(rep, "heatmap.png"), png, k)
    print(f"\nGravado em {rep} (report.json, heatmap.png dos últimos {a.png_days} dias) · {time.time() - t_start:.0f} s")


if __name__ == "__main__":
    sys.exit(main())
