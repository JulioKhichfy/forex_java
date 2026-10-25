"""
Histórico público dos futuros perpétuos da Binance (USDⓈ-M) para o estudo do mapa de liquidações (passo 12, 25/10/2026).

Fonte: https://data.binance.vision (público, sem conta). Baixa para o bronze do lake, IMUTÁVEL (arquivo que já existe
nunca é regravado), cada zip com o seu .meta.json (url, sha256 conferido com o .CHECKSUM da Binance, first_seen_at):

    bronze/binance_um/metrics/symbol=BTCUSDT/BTCUSDT-metrics-2024-01-01.zip     open interest a cada 5 min (diário)
    bronze/binance_um/klines_1m/symbol=BTCUSDT/BTCUSDT-1m-2024-01.zip           candles 1 min (mensal; o mês corrente, diário)

Point-in-time: o arquivo diário só sai no dia seguinte; o estudo usa o open interest de cada carimbo a partir do próprio
carimbo (é o mesmo valor que a API ao vivo dá na hora) e nunca nada de depois do momento de decisão.

Uso:
    python tools/binance_backfill.py [--lake C:/Users/julio/FOREX_JEV/jev-lake] [--symbols BTCUSDT] [--from 2021-12-01]
"""
import argparse
import concurrent.futures as cf
import datetime as dt
import hashlib
import json
import os
import sys
import time
import urllib.error
import urllib.request

BASE = "https://data.binance.vision/data/futures/um"
COLLECTOR = "binance-backfill@0.1"


def now_iso():
    return dt.datetime.now(dt.timezone.utc).strftime("%Y-%m-%dT%H:%M:%S.%fZ")


def get(url, tries=4):
    for k in range(tries):
        try:
            with urllib.request.urlopen(urllib.request.Request(url, headers={"User-Agent": "jev-forex-research"}),
                                        timeout=60) as r:
                return r.read()
        except urllib.error.HTTPError as e:
            if e.code == 404:
                return None
            time.sleep(2 ** k)
        except Exception:
            time.sleep(2 ** k)
    raise RuntimeError("falhou: " + url)


def fetch(url, dest, dataset, symbol):
    """Baixa um zip para o bronze se ainda não existe. Retorna 'novo', 'existe', 'ausente' ou lança erro."""
    if os.path.exists(dest):
        return "existe"
    data = get(url)
    if data is None:
        return "ausente"
    sha = hashlib.sha256(data).hexdigest()
    chk = get(url + ".CHECKSUM")
    if chk is not None and chk.split()[0].decode() != sha:
        raise RuntimeError("checksum não bate: " + url)
    os.makedirs(os.path.dirname(dest), exist_ok=True)
    tmp = dest + ".part"
    with open(tmp, "wb") as f:
        f.write(data)
    seen = now_iso()
    meta = {"url": url, "source": "binance_um", "dataset": dataset, "symbol": symbol, "collector": COLLECTOR,
            "sha256": sha, "checksum_ok": chk is not None, "bytes": len(data), "first_seen_at": seen, "ingested_at": seen}
    with open(dest[:-4] + ".meta.json", "w", encoding="utf-8") as f:
        json.dump(meta, f, indent=2)
    os.replace(tmp, dest)
    return "novo"


def jobs(lake, symbol, start, today):
    out = []
    d = start
    while d < today:   # metrics: um arquivo por dia, até ontem
        name = f"{symbol}-metrics-{d:%Y-%m-%d}.zip"
        out.append((f"{BASE}/daily/metrics/{symbol}/{name}",
                    os.path.join(lake, "bronze", "binance_um", "metrics", f"symbol={symbol}", name), "metrics"))
        d += dt.timedelta(days=1)
    m = dt.date(start.year, start.month, 1)
    first_of_month = dt.date(today.year, today.month, 1)
    while m < first_of_month:   # klines: meses completos
        name = f"{symbol}-1m-{m:%Y-%m}.zip"
        out.append((f"{BASE}/monthly/klines/{symbol}/1m/{name}",
                    os.path.join(lake, "bronze", "binance_um", "klines_1m", f"symbol={symbol}", name), "klines_1m"))
        m = dt.date(m.year + (m.month // 12), m.month % 12 + 1, 1)
    d = first_of_month
    while d < today:   # mês corrente: diário
        name = f"{symbol}-1m-{d:%Y-%m-%d}.zip"
        out.append((f"{BASE}/daily/klines/{symbol}/1m/{name}",
                    os.path.join(lake, "bronze", "binance_um", "klines_1m", f"symbol={symbol}", name), "klines_1m"))
        d += dt.timedelta(days=1)
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--lake", default=os.environ.get("LAKE_ROOT", os.path.expanduser("~/FOREX_JEV/jev-lake")))
    ap.add_argument("--symbols", default="BTCUSDT")
    ap.add_argument("--from", dest="start", default="2021-12-01")
    ap.add_argument("--threads", type=int, default=8)
    a = ap.parse_args()
    today = dt.datetime.now(dt.timezone.utc).date()
    start = dt.date.fromisoformat(a.start)
    for symbol in a.symbols.split(","):
        js = jobs(a.lake, symbol.strip(), start, today)
        counts = {"novo": 0, "existe": 0, "ausente": 0}
        missing = []
        t0 = time.time()
        with cf.ThreadPoolExecutor(a.threads) as ex:
            futs = {ex.submit(fetch, u, d, ds, symbol): u for u, d, ds in js}
            for i, f in enumerate(cf.as_completed(futs), 1):
                r = f.result()
                counts[r] += 1
                if r == "ausente":
                    missing.append(futs[f].rsplit("/", 1)[1])
                if i % 200 == 0:
                    print(f"  {symbol}: {i}/{len(js)} ({time.time() - t0:.0f} s)", flush=True)
        print(f"{symbol}: {counts} em {time.time() - t0:.0f} s")
        if missing:
            print("  ausentes na Binance:", ", ".join(sorted(missing)[:20]), "..." if len(missing) > 20 else "")


if __name__ == "__main__":
    sys.exit(main())
