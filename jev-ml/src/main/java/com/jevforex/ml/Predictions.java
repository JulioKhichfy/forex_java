package com.jevforex.ml;

import com.jevforex.lake.LakeSql;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

/**
 * Previsões fora da amostra → gold/predictions/market=fx/fset=…/run=…/horizon=…/*.parquet.
 * Guardar as probabilidades permite recalcular métricas e regras de decisão sem treinar de novo.
 *
 * <p>Escreve um CSV temporário e deixa o DuckDB converter para Parquet: inserir centenas de milhares de
 * linhas pelo JDBC esgota a memória do DuckDB.</p>
 */
final class Predictions {

    private Predictions() {
    }

    static Path write(LakeSql sql, Path lakeRoot, String fset, String runId, List<ExperimentRunner.Oos> all) {
        Path csv = lakeRoot.resolve("tmp").resolve("predictions-" + runId + ".csv");
        Path out = lakeRoot.resolve("gold/predictions/market=fx/fset=" + fset + "/run=" + runId);
        try {
            Files.createDirectories(csv.getParent());
            try (BufferedWriter w = Files.newBufferedWriter(csv, StandardCharsets.UTF_8)) {
                w.write("m,symbol,kind,horizon,fold,label,pa_down,pa_flat,pa_up,pb_down,pb_flat,pb_up,y_buy,y_sell\n");
                for (ExperimentRunner.Oos o : all) {
                    Dataset d = o.data();
                    for (int i = 0; i < d.size(); i++) {
                        if (o.foldOf()[i] < 0) continue;
                        double[] a = o.pA()[i], b = o.pB()[i];
                        // "\n" explícito: %n vira \r\n no Windows e mistura com o cabeçalho
                        w.write(String.format(Locale.ROOT, "%d,%s,%s,%dm,%s,%s,%.6f,%.6f,%.6f,%.6f,%.6f,%.6f,%.6f,%.6f\n",
                                d.moment[i], d.symbol[i], d.event[i] ? "EVENT" : "CONTROL", o.horizon(),
                                o.foldMonth()[o.foldOf()[i]], Dataset.CLASSES.get(d.y[i]), a[0], a[1], a[2],
                                b[0], b[1], b[2], d.yBuy[i], d.ySell[i]));
                    }
                }
            }
            Files.createDirectories(out.getParent());
            LakeSql.deleteRecursively(out);
            sql.execute("""
                    COPY (SELECT make_timestamp(m * 1000000) AS moment_utc, * EXCLUDE (m)
                            FROM read_csv(%s, header = true, delim = ',', auto_detect = false,
                                          columns = {'m': 'BIGINT', 'symbol': 'VARCHAR', 'kind': 'VARCHAR',
                                                     'horizon': 'VARCHAR', 'fold': 'VARCHAR', 'label': 'VARCHAR',
                                                     'pa_down': 'DOUBLE', 'pa_flat': 'DOUBLE', 'pa_up': 'DOUBLE',
                                                     'pb_down': 'DOUBLE', 'pb_flat': 'DOUBLE', 'pb_up': 'DOUBLE',
                                                     'y_buy': 'DOUBLE', 'y_sell': 'DOUBLE'})
                           ORDER BY moment_utc, symbol)
                      TO %s (FORMAT PARQUET, COMPRESSION ZSTD, PARTITION_BY (horizon))
                    """.formatted(LakeSql.literal(csv), LakeSql.literal(out)));
            return out;
        } catch (IOException e) {
            throw new UncheckedIOException("Não consegui gravar as previsões em " + out, e);
        } finally {
            try {
                Files.deleteIfExists(csv);
            } catch (IOException ignored) {
                // fica no tmp; não atrapalha
            }
        }
    }
}
