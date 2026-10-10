package com.jevforex.ml;

import com.jevforex.lake.LakeSql;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Previsões fora da amostra → gold/predictions/market=fx/fset=…/run=…/horizon=…/*.parquet, com
 * p_&lt;modelo&gt;_down/flat/up para cada modelo. Guardar as probabilidades permite recalcular métricas e regras
 * de decisão sem treinar de novo.
 *
 * <p>Escreve um CSV temporário e deixa o DuckDB converter para Parquet: inserir centenas de milhares de
 * linhas pelo JDBC esgota a memória do DuckDB.</p>
 */
final class Predictions {

    private static final String[] SUFFIX = {"down", "flat", "up"};

    private Predictions() {
    }

    static Path write(LakeSql sql, Path lakeRoot, String fset, String runId, List<ExperimentRunner.Oos> all) {
        return write(sql, lakeRoot, "fx", fset, runId, all);
    }

    static Path write(LakeSql sql, Path lakeRoot, String market, String fset, String runId,
                      List<ExperimentRunner.Oos> all) {
        Path csv = lakeRoot.resolve("tmp").resolve("predictions-" + runId + ".csv");
        Path out = lakeRoot.resolve("gold/predictions/market=" + market + "/fset=" + fset + "/run=" + runId);
        // os modelos podem variar por horizonte (C sem grupo C): usa a união, vazio onde não houver
        List<String> models = new ArrayList<>();
        all.forEach(o -> o.probs().keySet().forEach(m -> {
            if (!models.contains(m)) models.add(m);
        }));
        List<String> probCols = new ArrayList<>();
        for (String m : models) for (String s : SUFFIX) probCols.add("p_" + m.toLowerCase(Locale.ROOT) + "_" + s);
        try {
            Files.createDirectories(csv.getParent());
            try (BufferedWriter w = Files.newBufferedWriter(csv, StandardCharsets.UTF_8)) {
                w.write("m,symbol,kind,horizon,fold,label," + String.join(",", probCols)
                        + ",y_buy,y_sell,r_buy,r_sell,r_buy_late,r_sell_late\n");
                for (ExperimentRunner.Oos o : all) {
                    Dataset d = o.data();
                    for (int i = 0; i < d.size(); i++) {
                        if (o.foldOf()[i] < 0) continue;
                        StringBuilder line = new StringBuilder();
                        line.append(d.moment[i]).append(',').append(d.symbol[i]).append(',')
                                .append(d.event[i] ? "EVENT" : "CONTROL").append(',').append(o.horizon()).append("m,")
                                .append(o.foldMonth()[o.foldOf()[i]]).append(',').append(Dataset.CLASSES.get(d.y[i]));
                        for (String m : models) {
                            double[][] p = o.probs().get(m);
                            for (int k = 0; k < 3; k++) {
                                line.append(',');
                                if (p != null) line.append(String.format(Locale.ROOT, "%.6f", p[i][k]));
                            }
                        }
                        line.append(String.format(Locale.ROOT, ",%.6f,%.6f", d.yBuy[i], d.ySell[i]));
                        for (double r : new double[]{d.rBuy[i], d.rSell[i], d.rBuyLate[i], d.rSellLate[i]}) {
                            line.append(',');
                            if (!Double.isNaN(r)) line.append(String.format(Locale.ROOT, "%.6f", r));   // vazio = NULL
                        }
                        w.write(line.append('\n').toString());   // "\n" explícito: %n vira \r\n no Windows
                    }
                }
            }
            StringBuilder columns = new StringBuilder("'m': 'BIGINT', 'symbol': 'VARCHAR', 'kind': 'VARCHAR', "
                    + "'horizon': 'VARCHAR', 'fold': 'VARCHAR', 'label': 'VARCHAR'");
            probCols.forEach(c -> columns.append(", '").append(c).append("': 'DOUBLE'"));
            columns.append(", 'y_buy': 'DOUBLE', 'y_sell': 'DOUBLE', 'r_buy': 'DOUBLE', 'r_sell': 'DOUBLE', "
                    + "'r_buy_late': 'DOUBLE', 'r_sell_late': 'DOUBLE'");
            Files.createDirectories(out.getParent());
            LakeSql.deleteRecursively(out);
            sql.execute("""
                    COPY (SELECT make_timestamp(m * 1000000) AS moment_utc, * EXCLUDE (m)
                            FROM read_csv(%s, header = true, delim = ',', auto_detect = false, columns = {%s})
                           ORDER BY moment_utc, symbol)
                      TO %s (FORMAT PARQUET, COMPRESSION ZSTD, PARTITION_BY (horizon))
                    """.formatted(LakeSql.literal(csv), columns, LakeSql.literal(out)));
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
