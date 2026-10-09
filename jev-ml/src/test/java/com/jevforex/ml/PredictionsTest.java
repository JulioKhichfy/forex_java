package com.jevforex.ml;

import com.jevforex.lake.LakeSql;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PredictionsTest {

    @Test
    void gravaSoForaDaAmostra_umaColunaPorModelo(@TempDir Path lake) {
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
        double[][] pa = {{0.2, 0.5, 0.3}, {0.1, 0.3, 0.6}, {0.3, 0.3, 0.4}};
        double[][] pc = {{0.2, 0.5, 0.3}, {0.1, 0.2, 0.7}, {0.2, 0.3, 0.5}};
        Map<String, double[][]> probs = new LinkedHashMap<>();
        probs.put("A", pa);
        probs.put("C", pc);
        var oos = new ExperimentRunner.Oos(60, d, new int[]{-1, 0, 0}, new String[]{"2026-10"}, probs);

        try (LakeSql sql = LakeSql.open(lake.resolve("tmp"), "1GB")) {
            Path out = Predictions.write(sql, lake, "v2", "teste", List.of(oos));
            List<String> rows = sql.query("SELECT CAST(moment_utc AS VARCHAR) || ' ' || kind || ' ' || horizon || ' ' "
                    + "|| label || ' ' || p_a_up || ' ' || p_c_up FROM read_parquet('" + LakeSql.slashes(out)
                    + "/**/*.parquet', hive_partitioning = true) ORDER BY moment_utc", rs -> rs.getString(1));
            assertEquals(List.of("2026-10-09 16:40:00 EVENT 60m ALTA 0.6 0.7",
                    "2026-10-09 17:40:00 CONTROL 60m ALTA 0.4 0.5"), rows);   // a linha fora de qualquer fold não entra
        }
    }
}
