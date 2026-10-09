package com.jevforex.features;

import com.jevforex.lake.LakeSql;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.ResultSetMetaData;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FeatureBuilderTest {

    /**
     * Silver sintético: EURUSD e USDJPY, M1 de seg a sex de 05/01 a 13/02/2026 (6 semanas: o regime precisa de
     * 20 dias), preço em onda suave, spread de 8 pontos. {@code shock}: multiplica o EURUSD a partir de um instante.
     */
    private static void silver(Path lake, String shockFrom, double shockFactor) {
        lake.resolve("silver").toFile().mkdirs();   // no lake real, LocalDiskLakeStorage já cria
        try (LakeSql sql = LakeSql.open(lake.resolve("tmp"), "1GB")) {
            sql.execute("""
                    COPY (
                      SELECT 'fx' AS market, s.symbol, s.symbol || 'm' AS broker_symbol, g.ts AS time_utc,
                             px AS open, px * 1.0002 AS high, px * 0.9998 AS low, px AS close,
                             10::BIGINT AS tick_volume, 8 AS spread_points, 0::BIGINT AS real_volume,
                             'HISTORY' AS origin, g.ts AS seen_utc, year(g.ts) AS year, month(g.ts) AS month
                        FROM (VALUES ('EURUSD', 1.1), ('USDJPY', 150.0)) s(symbol, p0),
                             generate_series(TIMESTAMP '2026-01-05 00:00', TIMESTAMP '2026-02-13 23:59',
                                             INTERVAL 1 MINUTE) g(ts),
                             LATERAL (SELECT s.p0 * (1 + 0.002 * sin(epoch(g.ts) / 5400.0))
                                             * CASE WHEN s.symbol = 'EURUSD' AND g.ts >= TIMESTAMP '%s' THEN %s ELSE 1 END
                                             AS px)
                       WHERE isodow(g.ts) BETWEEN 1 AND 5)
                    TO %s (FORMAT PARQUET, PARTITION_BY (market, symbol, year, month))
                    """.formatted(shockFrom, shockFactor, LakeSql.literal(lake.resolve("silver/candles_m1"))));
            lake.resolve("silver/instrument_specs").toFile().mkdirs();
            sql.execute("COPY (SELECT * FROM (VALUES ('fx', 'EURUSD', 0.00001), ('fx', 'USDJPY', 0.001)) "
                    + "t(market, symbol, point)) TO " + LakeSql.literal(lake.resolve("silver/instrument_specs/part-0.parquet"))
                    + " (FORMAT PARQUET)");
            // CPI (USD): 10 divulgações semanais com surpresa ±0,1, depois +0,2 em 10/02 13:30.
            // Desemprego (USD): mesma história, depois +0,2 em 11/02 13:30 (valor maior enfraquece o USD).
            sql.execute("""
                    COPY (
                      WITH h AS (
                        SELECT e.event_id, e.code, i,
                               TIMESTAMP '2025-11-24 13:30' + INTERVAL (7 * i) DAY AS sched,
                               CASE WHEN i %% 2 = 0 THEN 0.1 ELSE -0.1 END AS diff
                          FROM (VALUES (1, 'consumer-price-index-mm'), (2, 'unemployment-rate')) e(event_id, code),
                               range(10) r(i)
                        UNION ALL SELECT 1, 'consumer-price-index-mm', 100, TIMESTAMP '2026-02-10 13:30', 0.2
                        UNION ALL SELECT 2, 'unemployment-rate', 101, TIMESTAMP '2026-02-11 13:30', 0.2)
                      SELECT CAST(event_id * 1000 + i AS UBIGINT) AS value_id, CAST(event_id AS UBIGINT) AS event_id,
                             code AS event_code, 'USD' AS currency, 'HIGH' AS importance, sched AS scheduled_utc,
                             3.0 + diff AS actual, 3.0 AS forecast, sched + INTERVAL 35 SECOND AS actual_available_utc,
                             'fx' AS market, year(sched) AS year
                        FROM h)
                    TO %s (FORMAT PARQUET, PARTITION_BY (market, year))
                    """.formatted(LakeSql.literal(lake.resolve("silver/calendar_events"))));
        }
    }

    private static FeatureBuilder.Report build(Path lake) {
        try (LakeSql sql = LakeSql.open(lake.resolve("tmp"), "1GB")) {
            return new FeatureBuilder(lake, FeatureConfig.defaults()).run(sql);
        }
    }

    /** Linha de features (todas as colunas como texto) de um par num momento. */
    private static List<String> featureRow(Path lake, String symbol, String moment) {
        try (LakeSql sql = LakeSql.open(lake.resolve("tmp"), "1GB")) {
            return sql.query("SELECT * EXCLUDE (year) FROM read_parquet('" + LakeSql.slashes(lake.resolve("gold/features"))
                    + "/**/*.parquet', hive_partitioning = true) WHERE symbol = '" + symbol
                    + "' AND moment_utc = TIMESTAMP '" + moment + "'", rs -> {
                ResultSetMetaData md = rs.getMetaData();
                List<String> v = new ArrayList<>();
                for (int i = 1; i <= md.getColumnCount(); i++) v.add(md.getColumnName(i) + "=" + rs.getString(i));
                return String.join(" ", v);
            });
        }
    }

    private static String label(Path lake, String symbol, String moment, String horizon) {
        try (LakeSql sql = LakeSql.open(lake.resolve("tmp"), "1GB")) {
            return sql.query("SELECT label FROM read_parquet('" + LakeSql.slashes(lake.resolve("gold/labels"))
                    + "/**/*.parquet', hive_partitioning = true) WHERE symbol = '" + symbol + "' AND moment_utc = "
                    + "TIMESTAMP '" + moment + "' AND horizon = '" + horizon + "'", rs -> rs.getString(1)).get(0);
        }
    }

    @Test
    void pointInTime_barraQueAbreNoMomentoNaoEntraNasFeatures(@TempDir Path base) throws Exception {
        Path normal = Files.createDirectories(base.resolve("normal"));
        Path shocked = Files.createDirectories(base.resolve("shock"));
        silver(normal, "2030-01-01 00:00", 1.0);
        silver(shocked, "2026-02-10 10:00", 1.05);   // +5% exatamente na barra que abre às 10:00
        build(normal);
        build(shocked);

        List<String> a = featureRow(normal, "EURUSD", "2026-02-10 10:00:00");
        List<String> b = featureRow(shocked, "EURUSD", "2026-02-10 10:00:00");
        assertEquals(1, a.size());
        assertEquals(a, b, "uma feature em t usou a barra que só fecha em t+1min");
    }

    @Test
    void labels_saltoDepoisDaEntrada_viraAlta(@TempDir Path base) throws Exception {
        Path normal = Files.createDirectories(base.resolve("normal"));
        Path jump = Files.createDirectories(base.resolve("jump"));
        silver(normal, "2030-01-01 00:00", 1.0);
        silver(jump, "2026-02-10 10:05", 1.01);      // entra 10:01; sobe 1% às 10:05; sai 10:16 / 11:01
        build(normal);
        FeatureBuilder.Report r = build(jump);

        assertEquals("ALTA", label(jump, "EURUSD", "2026-02-10 10:00:00", "15m"));
        assertEquals("ALTA", label(jump, "EURUSD", "2026-02-10 10:00:00", "60m"));
        assertTrue(r.labels().stream().allMatch(l -> l.meanCostAtr() > 0), "o label precisa pagar o spread");
        assertEquals(featureRow(normal, "EURUSD", "2026-02-10 10:00:00"),
                featureRow(jump, "EURUSD", "2026-02-10 10:00:00"));
    }

    @Test
    void surpresa_zPolaridadeEDecaimento(@TempDir Path lake) {
        silver(lake, "2030-01-01 00:00", 1.0);
        FeatureBuilder.Report r = build(lake);
        assertEquals(22, r.releases());
        assertTrue(r.releasesWithZ() >= 4);

        // σ das 10 anteriores (±0,1 alternado) = 0,10541; z = 0,2 / σ = 1,8974; 85 s depois: × exp(−85/3600)
        double expected = 0.2 / Math.sqrt(0.1 * 0.1 * 10 / 9) * Math.exp(-85.0 / 3600);
        // CPI acima do previsto fortalece o USD: EURUSD (USD na cotação) tem diferencial negativo
        assertEquals(-expected, value(lake, "EURUSD", "2026-02-10 13:32:00", "surprise_diff"), 1e-6);
        assertEquals(expected, value(lake, "USDJPY", "2026-02-10 13:32:00", "surprise_base"), 1e-6);
        // desemprego acima do previsto ENFRAQUECE o USD: sinal invertido
        assertEquals(expected, value(lake, "EURUSD", "2026-02-11 13:32:00", "surprise_diff"), 1e-6);
        assertEquals("EVENT", text(lake, "EURUSD", "2026-02-10 13:32:00", "kind"));
        assertEquals(2.0, value(lake, "EURUSD", "2026-02-10 13:32:00", "min_since_event"), 1e-9);
    }

    private static double value(Path lake, String symbol, String moment, String col) {
        return Double.parseDouble(text(lake, symbol, moment, col));
    }

    private static String text(Path lake, String symbol, String moment, String col) {
        try (LakeSql sql = LakeSql.open(lake.resolve("tmp"), "1GB")) {
            return sql.query("SELECT CAST(" + col + " AS VARCHAR) FROM read_parquet('"
                    + LakeSql.slashes(lake.resolve("gold/features")) + "/**/*.parquet', hive_partitioning = true) "
                    + "WHERE symbol = '" + symbol + "' AND moment_utc = TIMESTAMP '" + moment + "'",
                    rs -> rs.getString(1)).get(0);
        }
    }
}
