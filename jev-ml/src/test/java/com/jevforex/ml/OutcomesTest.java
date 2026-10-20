package com.jevforex.ml;

import com.jevforex.lake.LakeSql;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class OutcomesTest {

    /**
     * Barras de bid planas em 1,1000 (±2 pips), spread 1 pip, ATR 10 pips → stop 15 pips, alvo 22,5 pips.
     * Entrada às 10:01 (ask 1,1001 / bid 1,1000), horizonte 15 min.
     */
    @Test
    void stopAlvoETempo_minutoAMinuto(@TempDir Path lake) {
        Path labels = lake.resolve("gold/labels/market=fx/fset=v9");
        labels.getParent().toFile().mkdirs();
        lake.resolve("silver").toFile().mkdirs();
        try (LakeSql sql = LakeSql.open(lake.resolve("tmp"), "1GB")) {
            sql.execute("""
                    CREATE TABLE c AS
                    SELECT s.symbol, g.t AS time_utc, 1.1000 AS open, 1.1002 AS high, 1.0998 AS low, 1.1000 AS close,
                           10 AS spread_points, 'fx' AS market, 2026 AS year
                      FROM (VALUES ('EURUSD'), ('GBPUSD'), ('AUDUSD')) s(symbol),
                           generate_series(TIMESTAMP '2026-02-10 09:50', TIMESTAMP '2026-02-10 10:40', INTERVAL 1 MINUTE) g(t)
                    """);
            // EURUSD: dispara o alvo da compra (e o stop da venda) às 10:05
            sql.execute("UPDATE c SET high = 1.1030 WHERE symbol = 'EURUSD' AND time_utc = TIMESTAMP '2026-02-10 10:05'");
            // GBPUSD: stop e alvo da compra na mesma barra → conta o stop
            sql.execute("UPDATE c SET high = 1.1030, low = 1.0970 WHERE symbol = 'GBPUSD' "
                    + "AND time_utc = TIMESTAMP '2026-02-10 10:05'");
            // AUDUSD: nada dispara; sai na abertura das 10:16 (e a entrada atrasada, às 10:21)
            sql.execute("UPDATE c SET open = 1.1005 WHERE symbol = 'AUDUSD' AND time_utc = TIMESTAMP '2026-02-10 10:16'");
            sql.execute("UPDATE c SET open = 1.1010 WHERE symbol = 'AUDUSD' AND time_utc = TIMESTAMP '2026-02-10 10:21'");
            sql.execute("COPY c TO " + LakeSql.literal(lake.resolve("silver/candles_m1"))
                    + " (FORMAT PARQUET, PARTITION_BY (year))");
            sql.execute("COPY (SELECT symbol, TIMESTAMP '2026-02-10 10:00' AS moment_utc, "
                    + "TIMESTAMP '2026-02-10 10:01' AS entry_t, 0.0010 AS atr, 15 AS horizon_min, '15m' AS horizon "
                    + "FROM (VALUES ('EURUSD'), ('GBPUSD'), ('AUDUSD')) s(symbol)) TO " + LakeSql.literal(labels)
                    + " (FORMAT PARQUET, PARTITION_BY (horizon))");

            LocalDate from = LocalDate.of(2026, 2, 1);
            Outcomes.prepare(sql, lake, "fx", from);
            Outcomes.create(sql, labels, "o", 15, from, 1.5, 1.5, 0);
            Outcomes.create(sql, labels, "late", 15, from, 1.5, 1.5, 5);

            assertEquals(1.5, r(sql, "o", "EURUSD", "r_buy"), 1e-9);    // alvo = 1,5 R
            assertEquals(-1.0, r(sql, "o", "EURUSD", "r_sell"), 1e-9);  // stop da venda (ask = bid + spread)
            assertEquals(-1.0, r(sql, "o", "GBPUSD", "r_buy"), 1e-9);   // mesma barra: stop primeiro
            assertEquals((1.1005 - 1.1001) / 0.0015, r(sql, "o", "AUDUSD", "r_buy"), 1e-9);
            assertEquals((1.1000 - 1.1006) / 0.0015, r(sql, "o", "AUDUSD", "r_sell"), 1e-9);
            assertEquals((1.1010 - 1.1001) / 0.0015, r(sql, "late", "AUDUSD", "r_buy"), 1e-9);

            // laboratório: a grade de saídas numa passada dá o mesmo que cada saída separada
            sql.execute("CREATE TEMP TABLE exits AS SELECT * FROM (VALUES (1.0, 1.0), (1.5, 1.5), (2.0, 3.0)) v(stop_atr, target_r)");
            String moments = "SELECT symbol, moment_utc, atr, entry_t AS want_in FROM read_parquet('"
                    + LakeSql.slashes(labels) + "/**/*.parquet', hive_partitioning = true) WHERE horizon_min = 15";
            Outcomes.createGrid(sql, moments, "g", 15);
            for (double[] ex : new double[][]{{1.0, 1.0}, {1.5, 1.5}, {2.0, 3.0}}) {
                Outcomes.createFrom(sql, moments, "one", 15, ex[0], ex[1]);
                for (String s : List.of("EURUSD", "GBPUSD", "AUDUSD")) {
                    for (String col : List.of("r_buy", "r_sell")) {
                        double grid = sql.query("SELECT " + col + " FROM g WHERE symbol = '" + s + "' AND stop_atr = " + ex[0]
                                + " AND target_r = " + ex[1], rs -> rs.getDouble(1)).get(0);
                        assertEquals(r(sql, "one", s, col), grid, 1e-9, s + " " + col + " stop " + ex[0] + " alvo " + ex[1]);
                    }
                }
            }
        }
    }

    private static double r(LakeSql sql, String table, String symbol, String col) {
        List<Double> v = sql.query("SELECT " + col + " FROM " + table + " WHERE symbol = '" + symbol + "'",
                rs -> rs.getDouble(1));
        return v.get(0);
    }
}
