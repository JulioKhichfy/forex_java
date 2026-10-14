package com.jevforex.ml;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Consumer;

/**
 * Re-treinar com mais frequência melhora a previsão? (pedido do operador: "re-treino ao vivo").
 *
 * <p>Para cada frequência, anda pelo período de teste: em cada instante t treina com os {@code trainMonths} meses
 * de linhas cujo resultado JÁ era conhecido em t (como faria um re-treino ao vivo; sem embargo) e prevê as linhas
 * até o próximo re-treino. "A cada resultado" re-treina antes de cada momento de decisão com tudo o que já se sabia
 * — é o limite de qualquer re-treino por minuto, porque entre dois momentos nenhum resultado novo aparece.
 * Todas as frequências são medidas nas MESMAS linhas.</p>
 */
public final class RetrainStudy {

    /** Uma frequência: nome e passo em segundos (0 = a cada momento de decisão). */
    public record Step(String name, long seconds) {
    }

    public record Result(String step, int fits, int rows, double logLoss, double aucUp, Metrics.Trades rule,
                         Metrics.Trades ruleEvents, double seconds) {
    }

    private RetrainStudy() {
    }

    /**
     * @param model     A, E ou B
     * @param testFrom  início do período comparado (UTC)
     * @param minProb   limiar da regra de operação (ex.: 0,45)
     * @param minMargin margem da regra (ex.: 0,15)
     */
    public static List<Result> run(Dataset d, ExperimentConfig cfg, String model, LocalDate testFrom, List<Step> steps,
                                   double minProb, double minMargin, int threads, Consumer<String> progress) {
        long from = Dataset.epoch(testFrom);
        int[] test = java.util.stream.IntStream.range(0, d.size()).filter(i -> d.moment[i] >= from).toArray();
        if (test.length == 0) throw new IllegalArgumentException("Sem linhas a partir de " + testFrom);
        int[] cols = d.columnsOf(Dataset.MODELS.get(model));
        int[] yTest = new int[test.length];
        double[] rBuy = new double[test.length], rSell = new double[test.length];
        boolean[] ev = new boolean[test.length];
        for (int k = 0; k < test.length; k++) {
            yTest[k] = d.y[test[k]];
            rBuy[k] = d.rBuy[test[k]];
            rSell[k] = d.rSell[test[k]];
            ev[k] = d.event[test[k]];
        }
        List<Result> out = new ArrayList<>();
        ExecutorService pool = Executors.newFixedThreadPool(Math.max(1, threads));
        try {
            for (Step s : steps) {
                long t0 = System.nanoTime();
                // grupos de linhas de teste que usam o mesmo modelo: [instante do treino, primeira, última+1]
                List<long[]> groups = new ArrayList<>();
                int k = 0;
                while (k < test.length) {
                    long t = s.seconds() == 0 ? d.moment[test[k]]
                            : from + (d.moment[test[k]] - from) / s.seconds() * s.seconds();
                    int end = k;
                    while (end < test.length && (s.seconds() == 0 ? d.moment[test[end]] == t
                            : d.moment[test[end]] < t + s.seconds())) end++;
                    groups.add(new long[]{t, k, end});
                    k = end;
                }
                double[][] p = new double[test.length][];
                List<Future<?>> jobs = new ArrayList<>();
                for (long[] g : groups) {
                    jobs.add(pool.submit(() -> {
                        Gbm m = fitAt(d, cfg, cols, g[0]);
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
                    if (++done % 200 == 0) progress.accept(String.format(Locale.ROOT, "    %s: %d de %d treinos",
                            s.name(), done, jobs.size()));
                }
                Metrics.Trades all = Metrics.trades(p, rBuy, rSell, null, minProb, minMargin, 0.5);
                double[] rbE = rBuy.clone(), rsE = rSell.clone();
                for (int r = 0; r < test.length; r++) {
                    if (!ev[r]) {
                        rbE[r] = Double.NaN;
                        rsE[r] = Double.NaN;
                    }
                }
                Metrics.Trades events = Metrics.trades(p, rbE, rsE, null, minProb, minMargin, 0.5);
                Result res = new Result(s.name(), groups.size(), test.length, Metrics.logLoss(p, yTest),
                        Metrics.auc(p, yTest, Dataset.UP), all, events, (System.nanoTime() - t0) / 1e9);
                out.add(res);
                progress.accept(String.format(Locale.ROOT, "  %-18s %5d treinos · log loss %.4f · AUC alta %.3f · "
                                + "regra %s · só evento %s · %.0f s", s.name(), res.fits(), res.logLoss(), res.aucUp(),
                        fmt(all), fmt(events), res.seconds()));
            }
        } finally {
            pool.shutdownNow();
        }
        return out;
    }

    /** O modelo que um re-treino no instante t teria: últimos trainMonths meses com resultado conhecido em t. */
    private static Gbm fitAt(Dataset d, ExperimentConfig cfg, int[] cols, long t) {
        long start = Dataset.epoch(LocalDate.ofInstant(Instant.ofEpochSecond(t), ZoneOffset.UTC)
                .minusMonths(cfg.trainMonths()));
        int[] rows = java.util.stream.IntStream.range(0, d.size())
                .filter(i -> d.moment[i] >= start && d.labelAvailable[i] <= t).toArray();
        double[][] x = new double[rows.length][cols.length];
        int[] y = new int[rows.length];
        for (int k = 0; k < rows.length; k++) {
            y[k] = d.y[rows[k]];
            for (int c = 0; c < cols.length; c++) x[k][c] = d.x[rows[k]][cols[c]];
        }
        return Gbm.fit(x, y, d.names(cols), cfg.gbm(), cfg.seed());
    }

    public static String fmt(Metrics.Trades t) {
        return t.n() == 0 ? "0 op." : String.format(Locale.ROOT, "%d op. E[R] %+.3f PF %.2f", t.n(), t.expectancyR(),
                t.profitFactor());
    }
}
