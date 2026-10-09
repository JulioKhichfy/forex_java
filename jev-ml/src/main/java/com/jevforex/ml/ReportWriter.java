package com.jevforex.ml;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** reports/walkforward/&lt;run&gt;/report.html + metrics.json (documento mestre, capítulos 6 e 11). */
public final class ReportWriter {

    private static final Map<String, String> LABELS = Map.of("A", "A (preço)", "B", "B (+ calendário)",
            "C", "C (+ texto do Jev)");

    private ReportWriter() {
    }

    public static Path write(Path lakeRoot, ExperimentRunner.Result r) {
        Path dir = lakeRoot.resolve("reports/walkforward").resolve(r.runId());
        try {
            Files.createDirectories(dir);
            new ObjectMapper().registerModule(new JavaTimeModule())
                    .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                    .writerWithDefaultPrettyPrinter().writeValue(dir.resolve("metrics.json").toFile(), r);
            Files.writeString(dir.resolve("report.html"), html(r), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Não consegui gravar o relatório em " + dir, e);
        }
        return dir.resolve("report.html");
    }

    static String html(ExperimentRunner.Result r) {
        ExperimentConfig c = r.config();
        StringBuilder h = new StringBuilder();
        h.append("""
                <!doctype html><html lang="pt-BR"><head><meta charset="utf-8">
                <meta name="viewport" content="width=device-width, initial-scale=1">
                <title>Walk-forward A × B × C</title>
                <style>
                 body{font:14px/1.5 system-ui,-apple-system,Segoe UI,sans-serif;margin:24px auto;max-width:1100px;
                      padding:0 16px;color:#1d2330;background:#fff}
                 h1{font-size:22px;margin:0 0 4px} h2{font-size:18px;margin:28px 0 8px} h3{font-size:15px;margin:18px 0 6px}
                 .muted{color:#5d6675} table{border-collapse:collapse;width:100%;margin:8px 0 16px;font-variant-numeric:tabular-nums}
                 th,td{padding:5px 8px;border-bottom:1px solid #e3e6eb;text-align:right} th:first-child,td:first-child{text-align:left}
                 th{background:#f4f6f9;font-weight:600} .good{color:#11753a;font-weight:600} .bad{color:#b4261f}
                 .note{background:#f7f8fa;border-left:3px solid #8a94a6;padding:8px 12px;margin:10px 0}
                 .wrap{overflow-x:auto}
                 @media (prefers-color-scheme: dark){body{background:#14171c;color:#e6e9ee}th{background:#1f242c}
                  th,td{border-color:#2c323b}.muted{color:#9aa3b2}.note{background:#1b2027}.good{color:#4cc27a}.bad{color:#ff7a70}}
                </style></head><body>
                """);
        h.append("<h1>Experimento A × B × C — walk-forward</h1>");
        h.append(String.format(Locale.ROOT, "<p class=\"muted\">run %s · fset %s · treino %d meses · teste %d mês · "
                        + "embargo %d dia · cofre %d meses (não avaliado) · gate 4: P ≥ %.2f e margem ≥ %.2f · "
                        + "R = resultado ÷ %.1f ATR</p>", r.runId(), c.fset(), c.trainMonths(), c.testMonths(),
                c.embargoDays(), c.lockboxMonths(), c.decision().minProb(), c.decision().minMargin(), c.stopAtr()));
        h.append("""
                <div class="note"><b>Como ler.</b> A = só preço (volatilidade, custo, sessão, fator USD).
                B = A + calendário (surpresa padronizada). C = B + texto do Jev (sinal por moeda com decaimento).
                Todos foram treinados e avaliados nos mesmos momentos: <b>C contra B mede o valor do Jev</b>.
                Critério do documento mestre (cap. 11): C melhor que B (log loss) em ≥ 70% dos folds.
                Log loss menor = probabilidades melhores; a linha de base usa só a frequência das classes no treino.
                AUC 0,5 = acaso. As operações não simulam stop/alvo dentro do horizonte (passo 5) e ignoram os gates
                de exposição: servem para comparar modelos, não para prever lucro.</div>
                """);
        for (ExperimentRunner.HorizonResult hr : r.horizons()) {
            ExperimentRunner.Summary s = hr.summary();
            List<String> models = hr.models();
            h.append(String.format(Locale.ROOT, "<h2>Horizonte %d min</h2><p class=\"muted\">%d linhas · %d folds "
                            + "(teste %s a %s) · cofre %s a %s · %.0f s</p>", hr.horizon(), hr.rows(), s.folds(),
                    hr.firstTest(), hr.lastTest(), hr.lockboxFrom(), hr.lockboxTo(), hr.seconds()));

            h.append("<ul>");
            s.comparisons().forEach((k, v) -> h.append(String.format(Locale.ROOT, "<li>%s: <b class=\"%s\">%d de %d</b> "
                            + "folds com log loss menor (%.0f%%)%s</li>", k.replace("×", " melhor que "),
                    v >= 0.7 * s.folds() ? "good" : "", v, s.folds(), 100.0 * v / s.folds(),
                    k.equals("C×B") ? (v >= 0.7 * s.folds() ? " — atende o critério" : " — não atende o critério (≥ 70%)") : "")));
            h.append("</ul>");

            h.append("<div class=\"wrap\"><table><tr><th>Fora da amostra</th><th>Base</th>");
            models.forEach(m -> h.append("<th>").append(LABELS.getOrDefault(m, m)).append("</th>"));
            h.append("</tr>");
            row(h, "Log loss — todos (" + s.rows() + ")", s.llBase(), models, m -> s.models().get(m).ll(), true);
            row(h, "Log loss — só eventos (" + s.eventRows() + ")", s.llEventBase(), models,
                    m -> s.models().get(m).llEvent(), true);
            row(h, "AUC ALTA — todos", Double.NaN, models, m -> s.models().get(m).aucUp(), false);
            row(h, "AUC QUEDA — todos", Double.NaN, models, m -> s.models().get(m).aucDown(), false);
            row(h, "AUC ALTA — só eventos", Double.NaN, models, m -> s.models().get(m).aucUpEvent(), false);
            h.append("<tr><td>Operações (gate 4)</td><td></td>");
            models.forEach(m -> h.append("<td>").append(trades(s.models().get(m).trades())).append("</td>"));
            h.append("</tr></table></div>");

            h.append("<h3>Sensibilidade aos limiares (operações · E[R] · PF)</h3><div class=\"wrap\"><table>"
                    + "<tr><th>P mín. / margem</th>");
            models.forEach(m -> h.append("<th>").append(m).append("</th>"));
            h.append("</tr>");
            for (ExperimentRunner.Sweep sw : hr.sweep()) {
                h.append(String.format(Locale.ROOT, "<tr><td>%.2f / %.2f</td>", sw.minProb(), sw.minMargin()));
                for (String m : models) {
                    Metrics.Trades t = sw.models().get(m);
                    h.append(String.format(Locale.ROOT, "<td>%d · %s · %s</td>", t.n(), num(t.expectancyR(), 3),
                            num(t.profitFactor(), 2)));
                }
                h.append("</tr>");
            }
            h.append("</table></div>");

            h.append("<h3>Folds (log loss)</h3><div class=\"wrap\"><table><tr><th>Teste</th><th>Treino</th>"
                    + "<th>Teste (eventos)</th><th>Base</th>");
            models.forEach(m -> h.append("<th>").append(m).append("</th>"));
            h.append("</tr>");
            for (ExperimentRunner.FoldResult f : hr.folds()) {
                h.append(String.format(Locale.ROOT, "<tr><td>%s</td><td>%d</td><td>%d (%d)</td><td>%.4f</td>",
                        f.testMonth(), f.nTrain(), f.nTest(), f.nTestEvents(), f.llBase()));
                double best = models.stream().mapToDouble(m -> f.models().get(m).ll()).min().orElse(Double.NaN);
                for (String m : models) {
                    double v = f.models().get(m).ll();
                    h.append(String.format(Locale.ROOT, "<td class=\"%s\">%.4f</td>", v == best ? "good" : "", v));
                }
                h.append("</tr>");
            }
            h.append("</table></div>");

            h.append("<h3>Importância das features (média dos folds, top 15)</h3><div class=\"wrap\"><table><tr>");
            models.forEach(m -> h.append("<th>").append(LABELS.getOrDefault(m, m)).append("</th><th>%</th>"));
            h.append("</tr>");
            List<List<Map.Entry<String, Double>>> tops = new ArrayList<>();
            models.forEach(m -> tops.add(hr.importance().get(m).entrySet().stream().limit(15).toList()));
            for (int i = 0; i < 15; i++) {
                h.append("<tr>");
                for (List<Map.Entry<String, Double>> t : tops) {
                    Map.Entry<String, Double> e = i < t.size() ? t.get(i) : null;
                    h.append(String.format(Locale.ROOT, "<td>%s</td><td>%s</td>", e == null ? "" : e.getKey(),
                            e == null ? "" : String.format(Locale.ROOT, "%.1f", 100 * e.getValue())));
                }
                h.append("</tr>");
            }
            h.append("</table></div>");
        }
        h.append("<p class=\"muted\">Previsões fora da amostra: gold/predictions/market=fx/fset=").append(c.fset())
                .append("/run=").append(r.runId()).append("</p></body></html>");
        return h.toString();
    }

    private static void row(StringBuilder h, String name, double base, List<String> models,
                            java.util.function.ToDoubleFunction<String> value, boolean lowerIsBetter) {
        h.append("<tr><td>").append(name).append("</td><td>").append(num(base, 4)).append("</td>");
        double best = lowerIsBetter ? models.stream().mapToDouble(value).min().orElse(Double.NaN)
                : models.stream().mapToDouble(value).max().orElse(Double.NaN);
        for (String m : models) {
            double v = value.applyAsDouble(m);
            h.append("<td class=\"").append(v == best ? "good" : "").append("\">").append(num(v, 4)).append("</td>");
        }
        h.append("</tr>");
    }

    private static String trades(Metrics.Trades t) {
        if (t.n() == 0) return "nenhuma";
        return String.format(Locale.ROOT, "%d · E[R] %s · PF %s · acerto %.0f%% · DD %.1f%%", t.n(),
                num(t.expectancyR(), 3), num(t.profitFactor(), 2), 100 * t.hitRate(), t.maxDrawdownPct());
    }

    private static String num(double v, int decimals) {
        return Double.isNaN(v) ? "—" : String.format(Locale.ROOT, "%." + decimals + "f", v);
    }
}
