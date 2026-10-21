package com.jevforex.features;

import com.jevforex.lake.LakeSql;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class EventMapTest {

    /**
     * Ouro parado em 2000 com amplitude de 1,0 por 15 min (ATR ≈ 1); 10 divulgações de USD, metade acima do previsto
     * (sobe 3,0 depois de 2 min) e metade abaixo (cai 3,0). O mapa precisa contar 100% de alta quando veio acima, 0%
     * quando veio abaixo, e a reação de 15 min em ~3 ATR.
     */
    @Test
    void direcaoConformeASurpresa(@TempDir Path lake) {
        lake.resolve("silver/candles_m1/market=metals").toFile().mkdirs();
        try (LakeSql sql = LakeSql.open(lake.resolve("tmp"), "1GB")) {
            StringBuilder ev = new StringBuilder();
            for (int i = 0; i < 10; i++) {
                if (i > 0) ev.append(" UNION ALL ");
                ev.append(String.format("SELECT %d AS value_id, 1 AS event_id, 'cpi' AS event_code, 'USD' AS currency, 'HIGH' AS importance, "
                        + "TIMESTAMP '2024-03-%02d 12:30' AS scheduled_utc, %s AS actual, 1.0 AS forecast, 2024 AS year",
                        i + 1, i + 4, i % 2 == 0 ? "2.0" : "0.0"));
            }
            sql.execute("CREATE TABLE ev AS " + ev);
            sql.execute("COPY ev TO " + LakeSql.literal(lake.resolve("silver/calendar_events")) + " (FORMAT PARQUET, PARTITION_BY (year))");
            sql.execute("COPY (SELECT 'cpi' AS event_code, 'Inflação' AS name, 2024 AS y) TO "
                    + LakeSql.literal(lake.resolve("silver/calendar_event_defs")) + " (FORMAT PARQUET, PARTITION_BY (y))");
            // barras: 0,5 de amplitude por minuto (ATR de 15 min ≈ 1,0); a partir de 12:35 sobe ou cai 3,0 conforme a surpresa
            sql.execute("""
                    CREATE TABLE c AS
                    SELECT 'XAUUSD' AS symbol, g.t AS time_utc,
                           2000 + CASE WHEN g.t >= d + INTERVAL 755 MINUTE THEN (CASE WHEN day(d) % 2 = 0 THEN 3.0 ELSE -3.0 END) ELSE 0 END AS open,
                           2000.5 + CASE WHEN g.t >= d + INTERVAL 755 MINUTE THEN (CASE WHEN day(d) % 2 = 0 THEN 3.0 ELSE -3.0 END) ELSE 0 END AS high,
                           1999.5 + CASE WHEN g.t >= d + INTERVAL 755 MINUTE THEN (CASE WHEN day(d) % 2 = 0 THEN 3.0 ELSE -3.0 END) ELSE 0 END AS low,
                           2000 AS close, 20 AS spread_points, 'metals' AS market, 2024 AS year
                      FROM (SELECT DISTINCT date_trunc('day', scheduled_utc) AS d FROM ev) days,
                           generate_series(d + INTERVAL 8 HOUR, d + INTERVAL 15 HOUR, INTERVAL 1 MINUTE) g(t)
                    """);
            sql.execute("COPY c TO " + LakeSql.literal(lake.resolve("silver/candles_m1/market=metals/symbol=XAUUSD"))
                    + " (FORMAT PARQUET, PARTITION_BY (year))");
            lake.resolve("silver/instrument_specs").toFile().mkdirs();
            sql.execute("COPY (SELECT 'metals' AS market, 'XAUUSD' AS symbol, 0.001 AS point, 'USD' AS currency_profit) TO "
                    + LakeSql.literal(lake.resolve("silver/instrument_specs/part-0.parquet")) + " (FORMAT PARQUET)");

            EventMap.Report r = new EventMap(lake, "metals", List.of()).run(sql);
            assertEquals(1, r.rows().size());
            EventMap.Row row = r.rows().get(0);
            assertEquals("Inflação", row.name());
            assertEquals(10, row.n());
            assertEquals(5, row.nPos());
            assertEquals(1.0, row.upPos15(), 1e-9, "acima do previsto: subiu em todas");
            assertEquals(0.0, row.upNeg15(), 1e-9, "abaixo: caiu em todas");
            assertEquals(3.0, row.abs15(), 0.01, "reação de ~3 ATR (ATR = 1,0)");
        }
    }
}
