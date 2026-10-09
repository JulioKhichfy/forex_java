package com.jevforex.ml;

import com.jevforex.lake.LakeSql;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PredictionsTest {

    @Test
    void gravaSoForaDaAmostra_eLeDeVolta(@TempDir Path lake) {
        Dataset d = new Dataset(3, List.of("x"));
        long t = 1_791_560_400L;   // 2026-10-09 15:40 UTC
        for (int i = 0; i < 3; i++) {
            d.moment[i] = t + 3600L * i;
            d.symbol[i] = "EURUSD";
            d.event[i] = i == 1;
            d.y[i] = Dataset.UP;
            d.yBuy[i] = 0.7;
            d.ySell[i] = -0.9;
        }
        double[][] p = {{0.2, 0.5, 0.3}, {0.1, 0.3, 0.6}, {0.3, 0.3, 0.4}};
        var oos = new ExperimentRunner.Oos(60, d, new int[]{-1, 0, 0}, new String[]{"2026-10"}, p, p);

        try (LakeSql sql = LakeSql.open(lake.resolve("tmp"), "1GB")) {
            Path out = Predictions.write(sql, lake, "v1", "teste", List.of(oos));
            List<String> rows = sql.query("SELECT CAST(moment_utc AS VARCHAR) || ' ' || kind || ' ' || horizon || ' ' "
                    + "|| label || ' ' || pb_up FROM read_parquet('" + LakeSql.slashes(out)
                    + "/**/*.parquet', hive_partitioning = true) ORDER BY moment_utc", rs -> rs.getString(1));
            assertEquals(List.of("2026-10-09 16:40:00 EVENT 60m ALTA 0.6", "2026-10-09 17:40:00 CONTROL 60m ALTA 0.4"),
                    rows);   // a linha fora de qualquer fold (−1) não entra
        }
    }
}
