package com.jevforex.ml;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;

/** reports/walkforward/&lt;run&gt;/report.html + metrics.json (documento mestre, capítulos 6 e 11). */
public final class ReportWriter {

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
                <title>Walk-forward A × B</title>
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
        h.append("<h1>Experimento A × B — walk-forward</h1>");
        h.append(String.format(Locale.ROOT, "<p class=\"muted\">run %s · fset %s · treino %d meses · teste %d mês · "
                        + "embargo %d dia · cofre %d meses (não avaliado) · gate 4: P ≥ %.2f e margem ≥ %.2f · "
                        + "R = resultado ÷ %.1f ATR</p>", r.runId(), c.fset(), c.trainMonths(), c.testMonths(),
                c.embargoDays(), c.lockboxMonths(), c.decision().minProb(), c.decision().minMargin(), c.stopAtr()));
        h.append("""
                <div class="note"><b>Como ler.</b> A = só preço (volatilidade, custo, sessão, fator USD).
                B = A + calendário (surpresa padronizada). Os dois foram treinados e avaliados nos mesmos momentos.
                Log loss menor = probabilidades melhores; a linha de base usa só a frequência das classes no treino.
                AUC 0,5 = acaso. As operações não simulam stop/alvo dentro do horizonte (isso é do passo 5) e
                ignoram os gates de exposição: servem para comparar A e B, não para prever lucro.</div>
                """);
        for (ExperimentRunner.HorizonResult hr : r.horizons()) {
            ExperimentRunner.Summary s = hr.summary();
            h.append(String.format(Locale.ROOT, "<h2>Horizonte %d min</h2><p class=\"muted\">%d linhas · %d folds "
                            + "(teste %s a %s) · cofre %s a %s · %.0f s</p>", hr.horizon(), hr.rows(), s.folds(),
                    hr.firstTest(), hr.lastTest(), hr.lockboxFrom(), hr.lockboxTo(), hr.seconds()));
            h.append("<div class=\"wrap\"><table><tr><th>Fora da amostra</th><th>Base</th><th>A (preço)</th>"
                    + "<th>B (+ calendário)</th></tr>");
            row(h, "Log loss — todos (" + s.rows() + ")", s.llBase(), s.llA(), s.llB(), true);
            row(h, "Log loss — só eventos (" + s.eventRows() + ")", s.llEventBase(), s.llEventA(), s.llEventB(), true);
            row(h, "AUC ALTA — todos", Double.NaN, s.aucUpA(), s.aucUpB(), false);
            row(h, "AUC QUEDA — todos", Double.NaN, s.aucDownA(), s.aucDownB(), false);
            row(h, "AUC ALTA — só eventos", Double.NaN, s.aucUpEventA(), s.aucUpEventB(), false);
            tradesRow(h, "Operações (gate 4)", s.tradesA(), s.tradesB());
            h.append("</table></div>");
            h.append(String.format(Locale.ROOT, "<p>B teve log loss menor que A em <b>%d de %d</b> folds (%.0f%%).</p>",
                    s.foldsBBetter(), s.folds(), 100.0 * s.foldsBBetter() / s.folds()));

            h.append("<h3>Sensibilidade aos limiares</h3><div class=\"wrap\"><table><tr><th>P mín. / margem</th>"
                    + "<th>A: trades</th><th>A: E[R]</th><th>A: PF</th><th>B: trades</th><th>B: E[R]</th><th>B: PF</th></tr>");
            for (ExperimentRunner.Sweep sw : hr.sweep()) {
                h.append(String.format(Locale.ROOT, "<tr><td>%.2f / %.2f</td><td>%d</td><td>%s</td><td>%s</td>"
                                + "<td>%d</td><td>%s</td><td>%s</td></tr>", sw.minProb(), sw.minMargin(), sw.a().n(),
                        num(sw.a().expectancyR(), 3), num(sw.a().profitFactor(), 2), sw.b().n(),
                        num(sw.b().expectancyR(), 3), num(sw.b().profitFactor(), 2)));
            }
            h.append("</table></div>");

            h.append("<h3>Folds</h3><div class=\"wrap\"><table><tr><th>Teste</th><th>Treino</th><th>Teste (eventos)</th>"
                    + "<th>LL base</th><th>LL A</th><th>LL B</th><th>AUC↑ A</th><th>AUC↑ B</th><th>Trades A / B</th>"
                    + "<th>E[R] A / B</th></tr>");
            for (ExperimentRunner.FoldResult f : hr.folds()) {
                h.append(String.format(Locale.ROOT, "<tr><td>%s</td><td>%d</td><td>%d (%d)</td><td>%.4f</td>"
                                + "<td>%.4f</td><td class=\"%s\">%.4f</td><td>%s</td><td>%s</td><td>%d / %d</td>"
                                + "<td>%s / %s</td></tr>", f.testMonth(), f.nTrain(), f.nTest(), f.nTestEvents(),
                        f.llBase(), f.llA(), f.llB() < f.llA() ? "good" : "", f.llB(), num(f.aucUpA(), 3),
                        num(f.aucUpB(), 3), f.tradesA().n(), f.tradesB().n(), num(f.tradesA().expectancyR(), 3),
                        num(f.tradesB().expectancyR(), 3)));
            }
            h.append("</table></div>");

            h.append("<h3>Importância das features (média dos folds)</h3><div class=\"wrap\"><table>"
                    + "<tr><th>Modelo B</th><th>%</th><th>Modelo A</th><th>%</th></tr>");
            var bi = hr.importanceB().entrySet().stream().limit(15).toList();
            var ai = hr.importanceA().entrySet().stream().limit(15).toList();
            for (int i = 0; i < Math.max(bi.size(), ai.size()); i++) {
                Map.Entry<String, Double> b = i < bi.size() ? bi.get(i) : null;
                Map.Entry<String, Double> a = i < ai.size() ? ai.get(i) : null;
                h.append(String.format(Locale.ROOT, "<tr><td>%s</td><td>%s</td><td>%s</td><td>%s</td></tr>",
                        b == null ? "" : b.getKey(), b == null ? "" : String.format(Locale.ROOT, "%.1f", 100 * b.getValue()),
                        a == null ? "" : a.getKey(), a == null ? "" : String.format(Locale.ROOT, "%.1f", 100 * a.getValue())));
            }
            h.append("</table></div>");
        }
        h.append("<p class=\"muted\">Previsões fora da amostra: gold/predictions/market=fx/fset=").append(c.fset())
                .append("/run=").append(r.runId()).append("</p></body></html>");
        return h.toString();
    }

    private static void row(StringBuilder h, String name, double base, double a, double b, boolean lowerIsBetter) {
        boolean bBetter = lowerIsBetter ? b < a : b > a;
        h.append(String.format(Locale.ROOT, "<tr><td>%s</td><td>%s</td><td>%s</td><td class=\"%s\">%s</td></tr>",
                name, num(base, 4), num(a, 4), bBetter ? "good" : "", num(b, 4)));
    }

    private static void tradesRow(StringBuilder h, String name, Metrics.Trades a, Metrics.Trades b) {
        h.append(String.format(Locale.ROOT, "<tr><td>%s</td><td></td><td>%s</td><td>%s</td></tr>", name, trades(a),
                trades(b)));
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
