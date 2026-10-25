"""
Gatilho de evento (passo 12d, pedido do operador em 25/10/2026): operar assim que sai o dado, no lado que o dado indica.

Regra A — econômica, sem ajuste: o MT5 marca cada divulgação como POSITIVE/NEGATIVE para a moeda (já invertendo os
indicadores "ao contrário": desemprego e pedidos de seguro-desemprego acima do previsto = NEGATIVE). Divulgações da mesma
moeda no mesmo minuto viram um evento só (saldo de positivas − negativas). Par XXXYYY: a favor de XXX = compra.
Ouro e bitcoin: dólar positivo = venda.
Regra B — aprendida: por (evento, símbolo, sinal da surpresa), a direção média de +entrada a +15 min no período de
escolha (n ≥ 10); conferida depois. É o que a página Universo mostra ("subiu em 41% de 34 casos").

Entrada +1 ou +2 min depois do horário (o dado chega ~35 s depois pelo MT5), saída em +15 ou +60 min, em ATR (média da
amplitude das 14 barras de 15 min antes — o do mapa de eventos), menos o spread da Exness na entrada.

Uso:  python tools/estudo_gatilho_evento.py [--lake …]   →  reports/gatilho_evento/report.json e o resumo
"""
import argparse
import glob
import json
import math
import os
import sys
import time

import duckdb

SYMBOLS = {"fx": ["EURUSD", "GBPUSD", "USDJPY", "USDCHF", "AUDUSD", "USDCAD", "NZDUSD"],
           "metals": ["XAUUSD"], "crypto": ["BTCUSD"]}
CURRENCIES = ("USD", "EUR", "GBP", "JPY", "CHF", "AUD", "CAD", "NZD")
CHOOSE_END = "2025-07-01"


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--lake", default=os.environ.get("LAKE_ROOT", os.path.expanduser("~/FOREX_JEV/jev-lake")))
    a = ap.parse_args()
    t0 = time.time()
    lake = a.lake.replace(os.sep, "/")
    con = duckdb.connect()
    files = []
    for market, syms in SYMBOLS.items():
        for s in syms:
            files += [f.replace(os.sep, "/") for f in
                      glob.glob(f"{lake}/silver/candles_m1/market={market}/symbol={s}/**/*.parquet", recursive=True)]
    con.execute(f"""CREATE TEMP TABLE m1 AS SELECT symbol, time_utc AS t, open, high, low, spread_points
                    FROM read_parquet({files!r}, hive_partitioning=true) WHERE time_utc >= TIMESTAMP '2022-01-01'""")
    con.execute("CREATE TEMP TABLE m15 AS SELECT symbol, time_bucket(INTERVAL 15 MINUTE, t) t, max(high) - min(low) rng "
                "FROM m1 GROUP BY ALL")
    specs = [f.replace(os.sep, "/") for f in glob.glob(f"{lake}/silver/instrument_specs/**/*.parquet", recursive=True)]
    con.execute(f"""CREATE TEMP TABLE pt AS SELECT symbol, arg_max(point, seen_utc) point FROM read_parquet({specs!r})
                    GROUP BY 1""")
    # divulgações com impacto marcado; um evento por (moeda, minuto)
    con.execute(f"""
        CREATE TEMP TABLE ev AS
        SELECT value_id, event_code, currency, importance, scheduled_utc AS t0,
               CASE impact WHEN 'POSITIVE' THEN 1 WHEN 'NEGATIVE' THEN -1 END AS imp,
               sign(actual - forecast) AS surp
          FROM read_parquet('{lake}/silver/calendar_events/**/*.parquet', hive_partitioning=true)
         WHERE scheduled_utc >= TIMESTAMP '2022-01-01' AND importance IN ('HIGH', 'MODERATE')
           AND impact IN ('POSITIVE', 'NEGATIVE') AND actual IS NOT NULL AND forecast IS NOT NULL AND actual <> forecast
           AND currency IN {CURRENCIES}""")
    con.execute("""
        CREATE TEMP TABLE cl AS
        SELECT currency, t0, max(CASE importance WHEN 'HIGH' THEN 1 ELSE 0 END) AS has_high,
               sum(imp) FILTER (WHERE importance = 'HIGH') AS net_high, sum(imp) AS net_all,
               string_agg(DISTINCT event_code, ',') AS codes, count(*) AS k
          FROM ev GROUP BY 1, 2""")
    # símbolo × evento: lado a favor da moeda (forex: base +, cotada −; ouro/bitcoin: dólar − )
    con.execute("""
        CREATE TEMP TABLE pairs AS
        SELECT cl.*, s.symbol,
               CASE WHEN s.symbol IN ('XAUUSD', 'BTCUSD') THEN (CASE WHEN cl.currency = 'USD' THEN -1 ELSE 0 END)
                    WHEN substr(s.symbol, 1, 3) = cl.currency THEN 1
                    WHEN substr(s.symbol, 4, 3) = cl.currency THEN -1 ELSE 0 END AS dir
          FROM cl CROSS JOIN (SELECT DISTINCT symbol FROM m1) s""")
    con.execute("DELETE FROM pairs WHERE dir = 0")
    rows = []
    for delay in (1, 2):
        for hor in (15, 60):
            con.execute(f"""
                CREATE OR REPLACE TEMP TABLE mv AS
                WITH a AS (
                    SELECT p.*, e.open AS px_in, e.spread_points AS spr, e.t AS t_in
                      FROM pairs p ASOF JOIN m1 e ON e.symbol = p.symbol AND p.t0 + INTERVAL {delay} MINUTE <= e.t
                     WHERE e.t - p.t0 <= INTERVAL {delay + 2} MINUTE),
                b AS (
                    SELECT a.*, x.open AS px_out FROM a ASOF JOIN m1 x ON x.symbol = a.symbol AND a.t0 + INTERVAL {hor} MINUTE <= x.t
                     WHERE x.t - a.t0 <= INTERVAL {hor + 5} MINUTE),
                r AS (
                    SELECT b.symbol, b.t0, avg(m.rng) AS atr FROM b JOIN m15 m ON m.symbol = b.symbol
                       AND m.t >= b.t0 - INTERVAL 210 MINUTE AND m.t < b.t0 GROUP BY ALL)
                SELECT b.currency, b.t0, b.symbol, b.dir, b.net_high, b.net_all, b.has_high, b.codes,
                       (b.px_out - b.px_in) / r.atr AS mov, b.spr * pt.point / r.atr AS cost,
                       b.t0 < TIMESTAMP '{CHOOSE_END}' AS escolha
                  FROM b JOIN r USING (symbol, t0) JOIN pt USING (symbol) WHERE r.atr > 0""")
            for scope, net in (("só alta", "net_high"), ("alta + moderada", "net_all")):
                for per, cond in (("escolha", "escolha"), ("confirmação", "NOT escolha")):
                    res = con.sql(f"""
                        SELECT count(*) n, avg(sign({net}) * dir * mov - cost) er, stddev(sign({net}) * dir * mov - cost) sd,
                               avg(CASE WHEN sign({net}) * dir * mov - cost > 0 THEN 1 ELSE 0 END) hit, avg(cost) AS avg_cost
                          FROM mv WHERE {cond} AND {net} IS NOT NULL AND {net} <> 0""").fetchone()
                    n, er, sd, hit, cost = res
                    rows.append({"rule": "A econômica", "scope": scope, "delay_min": delay, "horizon_min": hor,
                                 "period": per, "n": n, "er_atr": er, "se": sd / math.sqrt(n) if n and sd else None,
                                 "hit": hit, "cost_atr": cost})
                    # por símbolo (só na confirmação, para ver onde está)
            # Regra B: direção aprendida por (evento único, símbolo, sinal do impacto) na escolha
            con.execute("""
                CREATE OR REPLACE TEMP TABLE b_learn AS
                SELECT codes, symbol, sign(net_all) s, avg(mov) m, count(*) n, stddev(mov) / sqrt(count(*)) se
                  FROM mv WHERE escolha AND net_all <> 0 AND position(',' IN codes) = 0 GROUP BY ALL HAVING count(*) >= 10""")
            for thr in (0.0, 2.0):
                for per, cond in (("escolha", "mv.escolha"), ("confirmação", "NOT mv.escolha")):
                    n, er, sd = con.sql(f"""
                        SELECT count(*), avg(sign(l.m) * mv.mov - mv.cost), stddev(sign(l.m) * mv.mov - mv.cost)
                          FROM mv JOIN b_learn l ON l.codes = mv.codes AND l.symbol = mv.symbol AND l.s = sign(mv.net_all)
                         WHERE {cond} AND abs(l.m) / nullif(l.se, 0) >= {thr}""").fetchone()
                    rows.append({"rule": f"B aprendida (|t| ≥ {thr:.0f})", "scope": "evento único", "delay_min": delay,
                                 "horizon_min": hor, "period": per, "n": n, "er_atr": er,
                                 "se": sd / math.sqrt(n) if n and sd else None})
    print("Gatilho de evento — E[resultado] em ATR, já menos o spread (escolha até 2025-06 / confirmação depois)\n")
    print(f"{'regra':<22} {'escopo':<16} {'entra':>6} {'sai':>5} | {'escolha':>22} | {'confirmação':>22}")
    keys = sorted({(r['rule'], r['scope'], r['delay_min'], r['horizon_min']) for r in rows})
    for k in keys:
        e = next(r for r in rows if (r['rule'], r['scope'], r['delay_min'], r['horizon_min']) == k and r['period'] == 'escolha')
        c = next(r for r in rows if (r['rule'], r['scope'], r['delay_min'], r['horizon_min']) == k and r['period'] == 'confirmação')

        def f(r):
            return "—" if not r["n"] else f"{r['er_atr']:+.3f} ±{(r['se'] or 0):.3f} (n {r['n']})"
        print(f"{k[0]:<22} {k[1]:<16} {'+' + str(k[2]) + ' min':>6} {str(k[3]) + ' m':>5} | {f(e):>22} | {f(c):>22}")
    # onde está: regra A, só alta, +1 min, 15 min, por símbolo e por moeda (confirmação)
    print("\nRegra A · só alta · entra +2 min · sai 15 min — por símbolo (escolha | confirmação):")
    con.execute("""
        CREATE OR REPLACE TEMP TABLE mv2 AS
        WITH a AS (SELECT p.*, e.open AS px_in, e.spread_points AS spr FROM pairs p ASOF JOIN m1 e
                     ON e.symbol = p.symbol AND p.t0 + INTERVAL 2 MINUTE <= e.t WHERE e.t - p.t0 <= INTERVAL 4 MINUTE),
             b AS (SELECT a.*, x.open AS px_out FROM a ASOF JOIN m1 x ON x.symbol = a.symbol AND a.t0 + INTERVAL 15 MINUTE <= x.t
                    WHERE x.t - a.t0 <= INTERVAL 20 MINUTE),
             r AS (SELECT b.symbol, b.t0, avg(m.rng) atr FROM b JOIN m15 m ON m.symbol = b.symbol
                    AND m.t >= b.t0 - INTERVAL 210 MINUTE AND m.t < b.t0 GROUP BY ALL)
        SELECT b.symbol, b.currency, b.t0 < TIMESTAMP '2025-07-01' escolha,
               sign(b.net_high) * b.dir * (b.px_out - b.px_in) / r.atr - b.spr * pt.point / r.atr AS res
          FROM b JOIN r USING (symbol, t0) JOIN pt USING (symbol)
         WHERE r.atr > 0 AND b.net_high IS NOT NULL AND b.net_high <> 0""")
    print(con.sql("""SELECT symbol,
                            round(avg(res) FILTER (WHERE escolha), 3) esc, count(*) FILTER (WHERE escolha) n_esc,
                            round(avg(res) FILTER (WHERE NOT escolha), 3) conf, count(*) FILTER (WHERE NOT escolha) n_conf
                       FROM mv2 GROUP BY 1 ORDER BY 1"""))
    print("Bruto (sem o spread) × custo, Regra A · só alta · +2 min · 15 min:")
    print(con.sql("""
        WITH a AS (SELECT p.*, e.open AS px_in, e.spread_points AS spr FROM pairs p ASOF JOIN m1 e
                     ON e.symbol = p.symbol AND p.t0 + INTERVAL 2 MINUTE <= e.t WHERE e.t - p.t0 <= INTERVAL 4 MINUTE),
             b AS (SELECT a.*, x.open AS px_out FROM a ASOF JOIN m1 x ON x.symbol = a.symbol AND a.t0 + INTERVAL 15 MINUTE <= x.t
                    WHERE x.t - a.t0 <= INTERVAL 20 MINUTE),
             r AS (SELECT b.symbol, b.t0, avg(m.rng) atr FROM b JOIN m15 m ON m.symbol = b.symbol
                    AND m.t >= b.t0 - INTERVAL 210 MINUTE AND m.t < b.t0 GROUP BY ALL),
             x AS (SELECT b.t0 < TIMESTAMP '2025-07-01' escolha, sign(b.net_high) * b.dir * (b.px_out - b.px_in) / r.atr g,
                          b.spr * pt.point / r.atr c,
                          sign(b.net_high) * b.dir * (b.px_in - (SELECT o.open FROM m1 o WHERE o.symbol = b.symbol
                              AND o.t >= b.t0 ORDER BY o.t LIMIT 1)) / r.atr AS first_move
                     FROM b JOIN r USING (symbol, t0) JOIN pt USING (symbol)
                    WHERE r.atr > 0 AND b.net_high IS NOT NULL AND b.net_high <> 0)
        SELECT escolha, count(*) n, round(avg(first_move), 3) AS ja_andou_ate_entrar, round(avg(g), 3) AS bruto_depois,
               round(avg(c), 3) AS spread, round(median(c), 3) AS spread_mediana
          FROM x GROUP BY 1 ORDER BY 1 DESC"""))
    print(con.sql("""SELECT currency,
                            round(avg(res) FILTER (WHERE escolha), 3) esc, count(*) FILTER (WHERE escolha) n_esc,
                            round(avg(res) FILTER (WHERE NOT escolha), 3) conf, count(*) FILTER (WHERE NOT escolha) n_conf
                       FROM mv2 GROUP BY 1 ORDER BY 1"""))
    out = os.path.join(a.lake, "reports", "gatilho_evento")
    os.makedirs(out, exist_ok=True)
    with open(os.path.join(out, "report.json"), "w", encoding="utf-8") as fh:
        json.dump(rows, fh, indent=1, default=str)
    print(f"\nGravado em {out} · {time.time() - t0:.0f} s")


if __name__ == "__main__":
    sys.exit(main())
