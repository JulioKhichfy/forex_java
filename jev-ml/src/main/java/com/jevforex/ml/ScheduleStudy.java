package com.jevforex.ml;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Consumer;
import java.util.function.IntPredicate;

/**
 * Janela estendida, peso ao recente e frequência de re-treino (pedido do operador em 28/10/2026: "prever com o mercado
 * aberto", "dar mais peso ao recente", "treino semanal").
 *
 * <p>Cada RECEITA (janela de treino, com ou sem as horas EXTRA, peso ao recente, re-treino mensal ou semanal) anda pelo
 * período de teste como um re-treino ao vivo: em cada instante t treina com as linhas cujo resultado já era conhecido em
 * t e prevê as linhas até o próximo re-treino. Todas as receitas são medidas nas MESMAS linhas, separadas por faixa:
 * a janela de sempre (o que o modelo de produção prevê hoje), as horas EXTRA, o fim de semana e a noite (21–23 h UTC).</p>
 *
 * <p>Peso ao recente: o GBM não aceita peso por linha, então a linha é repetida — {@code copies = max(1, round(3 ×
 * 0,5^(idade/meia-vida)))}: com meia-vida de 6 meses, as linhas de até ~1,5 mês contam 3×, as de até ~6 meses 2× e as mais velhas 1×.</p>
 */
public final class ScheduleStudy {

    /**
     * Uma receita de treino.
     *
     * @param trainMonths      janela de treino
     * @param withExtra        treina também com as horas EXTRA
     * @param halfLifeMonths   meia-vida do peso ao recente; null = sem peso
     * @param stepDays         re-treino a cada N dias
     */
    public record Recipe(String name, int trainMonths, boolean withExtra, Double halfLifeMonths, int stepDays) {
    }

    /** Uma faixa de linhas de teste. */
    public record Segment(String name, IntPredicate rows) {
    }

    /** Resultado de uma receita numa faixa. */
    public record Cell(String recipe, String segment, int rows, double logLoss, double aucUp, Metrics.Trades top,
                       Metrics.Trades gate, Metrics.Trades wide) {
    }

    public record Result(String market, int horizon, String testFrom, List<String> recipes, List<String> segments,
                         List<Cell> cells, Map<String, Integer> fits) {
    }

    private ScheduleStudy() {
    }

    /** As receitas do estudo de 28/10/2026. */
    public static List<Recipe> defaultRecipes() {
        return List.of(
                new Recipe("atual (24 m, sem EXTRA, mensal)", 24, false, null, 30),
                new Recipe("24 m + EXTRA, mensal", 24, true, null, 30),
                new Recipe("12 m + EXTRA, mensal", 12, true, null, 30),
                new Recipe("6 m + EXTRA, mensal", 6, true, null, 30),
                new Recipe("24 m + EXTRA, peso recente, mensal", 24, true, 6.0, 30),
                new Recipe("24 m + EXTRA, semanal", 24, true, null, 7),
                new Recipe("24 m + EXTRA, peso recente, semanal", 24, true, 6.0, 7));
    }

    /** As faixas: janela de sempre (momentos de produção), EXTRA e as partes do EXTRA. */
    public static List<Segment> segments(Dataset d) {
        return List.of(
                new Segment("janela de sempre", i -> !d.extra[i] && (d.event[i] || d.moment[i] % 3600 == 0)),
                new Segment("EXTRA (todas)", i -> d.extra[i]),
                new Segment("EXTRA fim de semana", i -> d.extra[i] && weekend(d.moment[i])),
                new Segment("EXTRA noite seg–sex", i -> d.extra[i] && !weekend(d.moment[i])));
    }

    private static boolean weekend(long epoch) {
        int dow = ZonedDateTime.ofInstant(Instant.ofEpochSecond(epoch), ZoneOffset.UTC).getDayOfWeek().getValue();
        return dow >= 6;
    }

    /**
     * @param gateMinProb / gateMinMargin gate 4 (experiment.decision)
     * @param wideMargin                  regra "larga" (a do ouro desde 22/10): diferença mínima, sem LATERAL, sem P mínima
     */
    public static Result run(Dataset d, ExperimentConfig cfg, String market, int horizon, String model, LocalDate testFrom,
                             List<Recipe> recipes, double gateMinProb, double gateMinMargin, double wideMargin,
                             int threads, Consumer<String> progress) {
        long from = Dataset.epoch(testFrom);
        // linhas de teste: produção (evento ou hora cheia) e EXTRA; os quartos de hora ficam fora da comparação
        int[] test = java.util.stream.IntStream.range(0, d.size())
                .filter(i -> d.moment[i] >= from && (d.extra[i] || d.event[i] || d.moment[i] % 3600 == 0)).toArray();
        if (test.length == 0) throw new IllegalArgumentException("Sem linhas de teste a partir de " + testFrom);
        int[] cols = d.columnsOf(Dataset.MODELS.get(model));
        List<Segment> segs = segments(d);
        List<Cell> cells = new ArrayList<>();
        Map<String, Integer> fits = new LinkedHashMap<>();
        ExecutorService pool = Executors.newFixedThreadPool(Math.max(1, threads));
        try {
            for (Recipe rc : recipes) {
                long t0 = System.nanoTime();
                long step = rc.stepDays() * 86_400L;
                List<long[]> groups = new ArrayList<>();
                int k = 0;
                while (k < test.length) {
                    long t = from + (d.moment[test[k]] - from) / step * step;
                    int end = k;
                    while (end < test.length && d.moment[test[end]] < t + step) end++;
                    groups.add(new long[]{t, k, end});
                    k = end;
                }
                double[][] p = new double[test.length][];
                List<Future<?>> jobs = new ArrayList<>();
                for (long[] g : groups) {
                    jobs.add(pool.submit(() -> {
                        Gbm m = fitAt(d, cfg, cols, g[0], rc);
                        double[][] x = new double[(int) (g[2] - g[1])][cols.length];
                        for (int r = (int) g[1]; r < g[2]; r++) {
                            for (int c = 0; c < cols.length; c++) x[r - (int) g[1]][c] = d.x[test[r]][cols[c]];
                        }
                        double[][] pr = m.predict(x);
                        for (int r = (int) g[1]; r < g[2]; r++) p[r] = pr[r - (int) g[1]];
                    }));
                }
                int done = 0;
                for (Future<?> f : jobs) {
                    try {
                        f.get();
                    } catch (Exception e) {
                        throw new IllegalStateException("Falhou um treino do estudo: " + e.getMessage(), e);
                    }
                    if (++done % 50 == 0) progress.accept(String.format(Locale.ROOT, "    %s: %d de %d treinos", rc.name(), done,
                            jobs.size()));
                }
                fits.put(rc.name(), groups.size());
                for (Segment s : segs) {
                    Cell c = measure(d, test, p, rc.name(), s, gateMinProb, gateMinMargin, wideMargin);
                    cells.add(c);
                }
                progress.accept(String.format(Locale.ROOT, "  %-40s %4d treinos · %.0f s", rc.name(), groups.size(),
                        (System.nanoTime() - t0) / 1e9));
                for (Cell c : cells.subList(cells.size() - segs.size(), cells.size())) {
                    progress.accept(String.format(Locale.ROOT, "      %-22s %7d linhas · log loss %.4f · AUC alta %.3f · "
                                    + "mais provável %s · gate %s · larga %s", c.segment(), c.rows(), c.logLoss(), c.aucUp(),
                            fmt(c.top()), fmt(c.gate()), fmt(c.wide())));
                }
            }
        } finally {
            pool.shutdownNow();
        }
        return new Result(market, horizon, testFrom.toString(), recipes.stream().map(Recipe::name).toList(),
                segs.stream().map(Segment::name).toList(), cells, fits);
    }

    /** As métricas de uma receita numa faixa (linhas em ordem cronológica). */
    static Cell measure(Dataset d, int[] test, double[][] p, String recipe, Segment s, double gateMinProb,
                        double gateMinMargin, double wideMargin) {
        List<Integer> idx = new ArrayList<>();
        for (int k = 0; k < test.length; k++) if (s.rows().test(test[k])) idx.add(k);
        int n = idx.size();
        double[][] ps = new double[n][];
        int[] y = new int[n];
        double[] rb = new double[n], rs = new double[n], rbTop = new double[n], rsTop = new double[n],
                rbWide = new double[n], rsWide = new double[n];
        for (int j = 0; j < n; j++) {
            int k = idx.get(j);
            ps[j] = p[k];
            y[j] = d.y[test[k]];
            rb[j] = d.rBuy[test[k]];
            rs[j] = d.rSell[test[k]];
            boolean flatTop = p[k][Dataset.FLAT] >= Math.max(p[k][Dataset.UP], p[k][Dataset.DOWN]);
            rbTop[j] = flatTop ? Double.NaN : rb[j];
            rsTop[j] = flatTop ? Double.NaN : rs[j];
            rbWide[j] = rbTop[j];
            rsWide[j] = rsTop[j];
        }
        if (n == 0) return new Cell(recipe, s.name(), 0, Double.NaN, Double.NaN, empty(), empty(), empty());
        return new Cell(recipe, s.name(), n, Metrics.logLoss(ps, y), Metrics.auc(ps, y, Dataset.UP),
                Metrics.trades(ps, rbTop, rsTop, null, 0, 0, 0.5),
                Metrics.trades(ps, rb, rs, null, gateMinProb, gateMinMargin, 0.5),
                Metrics.trades(ps, rbWide, rsWide, null, 0, wideMargin, 0.5));
    }

    private static Metrics.Trades empty() {
        return new Metrics.Trades(0, Double.NaN, Double.NaN, 0, Double.NaN);
    }

    /** O modelo que uma receita teria no instante t. */
    private static Gbm fitAt(Dataset d, ExperimentConfig cfg, int[] cols, long t, Recipe rc) {
        long start = Dataset.epoch(LocalDate.ofInstant(Instant.ofEpochSecond(t), ZoneOffset.UTC).minusMonths(rc.trainMonths()));
        List<Integer> rows = new ArrayList<>();
        for (int i = 0; i < d.size(); i++) {
            if (d.moment[i] < start || d.labelAvailable[i] > t) continue;
            if (d.extra[i] ? !rc.withExtra() : !(d.event[i] || d.moment[i] % 3600 == 0)) continue;   // sem quartos de hora
            int copies = copies(t - d.moment[i], rc.halfLifeMonths());
            for (int c = 0; c < copies; c++) rows.add(i);
        }
        double[][] x = new double[rows.size()][cols.length];
        int[] y = new int[rows.size()];
        for (int k = 0; k < rows.size(); k++) {
            int i = rows.get(k);
            y[k] = d.y[i];
            for (int c = 0; c < cols.length; c++) x[k][c] = d.x[i][cols[c]];
        }
        return Gbm.fit(x, y, d.names(cols), cfg.gbm(), cfg.seed());
    }

    /** Repetições de uma linha com esta idade (s): max(1, round(3 × 0,5^(idade/meia-vida))); sem peso = 1. */
    static int copies(long ageSeconds, Double halfLifeMonths) {
        if (halfLifeMonths == null) return 1;
        double months = ageSeconds / (30.44 * 86_400);
        return (int) Math.max(1, Math.round(3 * Math.pow(0.5, months / halfLifeMonths)));
    }

    public static String fmt(Metrics.Trades t) {
        return t.n() == 0 ? "0 op." : String.format(Locale.ROOT, "%d op. E[R] %+.3f", t.n(), t.expectancyR());
    }
}
