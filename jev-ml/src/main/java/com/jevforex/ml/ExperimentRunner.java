package com.jevforex.ml;

import com.jevforex.lake.LakeSql;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Experimento A × B com walk-forward, embargo e cofre (documento mestre, capítulo 11).
 *
 * <ul>
 *   <li>Fold: treina com {@code trainMonths} meses, testa no mês seguinte; só entram no treino linhas cujo
 *       label já era conhecido {@code embargoDays} antes do início do teste.</li>
 *   <li>Cofre: os últimos {@code lockboxMonths} meses completos ficam fora de tudo aqui.</li>
 *   <li>A e B são treinados e avaliados nas MESMAS linhas: a diferença mede só o calendário.</li>
 * </ul>
 */
public final class ExperimentRunner {

    private static final Logger log = LoggerFactory.getLogger(ExperimentRunner.class);
    /** Limiares extras para ver a sensibilidade das operações simuladas. */
    private static final double[][] SWEEP = {{0.45, 0.15}, {0.50, 0.25}, {0.55, 0.30}, {0.60, 0.35}};

    private final Path lakeRoot;
    private final ExperimentConfig cfg;

    public ExperimentRunner(Path lakeRoot, ExperimentConfig cfg) {
        this.lakeRoot = lakeRoot;
        this.cfg = cfg;
    }

    public record FoldResult(String testMonth, int nTrain, int nTest, int nTestEvents, double llBase, double llA,
                             double llB, double aucUpA, double aucUpB, Metrics.Trades tradesA, Metrics.Trades tradesB) {
    }

    public record Sweep(double minProb, double minMargin, Metrics.Trades a, Metrics.Trades b) {
    }

    /**
     * Métricas sobre TODAS as linhas fora da amostra; *Event = só momentos de evento (onde o calendário pode
     * ajudar).
     */
    public record Summary(int folds, int foldsBBetter, int rows, int eventRows, double llBase, double llA,
                          double llB, double llEventBase, double llEventA, double llEventB, double aucUpA,
                          double aucUpB, double aucDownA, double aucDownB, double aucUpEventA, double aucUpEventB,
                          Metrics.Trades tradesA, Metrics.Trades tradesB) {
    }

    public record HorizonResult(int horizon, int rows, String firstTest, String lastTest, String lockboxFrom,
                                String lockboxTo, List<FoldResult> folds, Summary summary, List<Sweep> sweep,
                                Map<String, Double> importanceA, Map<String, Double> importanceB, double seconds) {
    }

    /**
     * @param report      relatório HTML (gravado ANTES das previsões: uma falha nelas não perde o resultado)
     * @param predictions pasta das previsões no gold; null se a gravação falhou (motivo em predictionsError)
     */
    public record Result(String runId, ExperimentConfig config, List<HorizonResult> horizons, Path report,
                         Path predictions, String predictionsError) {
    }

    /** Previsões fora da amostra de um horizonte (para gravar no gold). */
    record Oos(int horizon, Dataset data, int[] foldOf, String[] foldMonth, double[][] pA, double[][] pB) {
    }

    public Result run(LakeSql sql, java.util.function.Consumer<String> progress) throws Exception {
        String runId = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC).format(Instant.now());
        List<HorizonResult> out = new ArrayList<>();
        List<Oos> oos = new ArrayList<>();
        for (int h : cfg.horizons()) {
            long t0 = System.nanoTime();
            Dataset d = Dataset.load(sql, lakeRoot, cfg.fset(), h, cfg.from());
            progress.accept(String.format("Horizonte %d min: %d linhas carregadas", h, d.size()));
            out.add(runHorizon(h, d, oos, progress, (System.nanoTime() - t0)));
        }
        Path report = ReportWriter.write(lakeRoot, new Result(runId, cfg, out, null, null, null));
        progress.accept("Relatório gravado: " + report);
        try {
            Path preds = Predictions.write(sql, lakeRoot, cfg.fset(), runId, oos);
            return new Result(runId, cfg, out, report, preds, null);
        } catch (RuntimeException e) {
            log.warn("Previsões não gravadas (o relatório está salvo): {}", e.getMessage());
            return new Result(runId, cfg, out, report, null, e.getMessage());
        }
    }

    private HorizonResult runHorizon(int h, Dataset d, List<Oos> oosOut, java.util.function.Consumer<String> progress,
                                     long loadNanos) throws Exception {
        long t0 = System.nanoTime() - loadNanos;
        // ---------------------------------------------------------------- folds e cofre
        YearMonth lastIncomplete = YearMonth.from(Instant.ofEpochSecond(d.moment[d.size() - 1]).atZone(ZoneOffset.UTC));
        YearMonth lockboxTo = lastIncomplete.minusMonths(1);
        YearMonth lockboxFrom = lockboxTo.minusMonths(cfg.lockboxMonths() - 1L);
        YearMonth firstTest = YearMonth.from(cfg.from()).plusMonths(cfg.trainMonths());
        List<YearMonth> tests = new ArrayList<>();
        for (YearMonth m = firstTest; m.isBefore(lockboxFrom); m = m.plusMonths(cfg.testMonths())) tests.add(m);
        if (tests.isEmpty()) throw new IllegalStateException("Dados insuficientes para um fold (treino de "
                + cfg.trainMonths() + " meses + cofre de " + cfg.lockboxMonths() + ")");

        int[] all = d.columnsOf(concat(Dataset.PRICE, Dataset.CALENDAR));
        int[] colsA = d.columnsOf(Dataset.PRICE);
        int[] colsB = all;
        List<String> namesA = d.names(colsA), namesB = d.names(colsB);

        double[][] pA = new double[d.size()][], pB = new double[d.size()][], pBase = new double[d.size()][];
        int[] foldOf = new int[d.size()];
        Arrays.fill(foldOf, -1);
        String[] foldMonth = new String[tests.size()];
        List<Map<String, Double>> impA = new ArrayList<>(), impB = new ArrayList<>();
        List<FoldResult> folds = new ArrayList<>();

        ExecutorService pool = Executors.newFixedThreadPool(cfg.threads());
        try {
            List<Future<FoldOutput>> futures = new ArrayList<>();
            for (int f = 0; f < tests.size(); f++) {
                final int fold = f;
                YearMonth m = tests.get(f);
                foldMonth[f] = m.toString();
                futures.add(pool.submit(() -> runFold(fold, m, lockboxFrom, d, colsA, colsB, namesA, namesB)));
            }
            int done = 0;
            for (Future<FoldOutput> fu : futures) {
                FoldOutput o = fu.get();
                for (int k = 0; k < o.testRows.length; k++) {
                    int i = o.testRows[k];
                    pA[i] = o.pA[k];
                    pB[i] = o.pB[k];
                    pBase[i] = o.pBase[k];
                    foldOf[i] = o.fold;
                }
                impA.add(o.impA);
                impB.add(o.impB);
                folds.add(o.result);
                progress.accept(String.format(java.util.Locale.ROOT,
                        "  %d min · fold %s (%d/%d): treino %d, teste %d · log loss A %.4f, B %.4f",
                        h, o.result.testMonth(), ++done, tests.size(), o.result.nTrain(), o.result.nTest(),
                        o.result.llA(), o.result.llB()));
            }
        } finally {
            pool.shutdownNow();
        }

        // ---------------------------------------------------------------- todas as linhas fora da amostra
        int[] rows = java.util.stream.IntStream.range(0, d.size()).filter(i -> foldOf[i] >= 0).toArray();
        int[] evRows = Arrays.stream(rows).filter(i -> d.event[i]).toArray();
        Summary s = new Summary(folds.size(),
                (int) folds.stream().filter(f -> f.llB() < f.llA()).count(), rows.length, evRows.length,
                Metrics.logLoss(sub(pBase, rows), sub(d.y, rows)), Metrics.logLoss(sub(pA, rows), sub(d.y, rows)),
                Metrics.logLoss(sub(pB, rows), sub(d.y, rows)),
                Metrics.logLoss(sub(pBase, evRows), sub(d.y, evRows)), Metrics.logLoss(sub(pA, evRows), sub(d.y, evRows)),
                Metrics.logLoss(sub(pB, evRows), sub(d.y, evRows)),
                Metrics.auc(sub(pA, rows), sub(d.y, rows), Dataset.UP), Metrics.auc(sub(pB, rows), sub(d.y, rows), Dataset.UP),
                Metrics.auc(sub(pA, rows), sub(d.y, rows), Dataset.DOWN), Metrics.auc(sub(pB, rows), sub(d.y, rows), Dataset.DOWN),
                Metrics.auc(sub(pA, evRows), sub(d.y, evRows), Dataset.UP),
                Metrics.auc(sub(pB, evRows), sub(d.y, evRows), Dataset.UP),
                trades(sub(pA, rows), d, rows, cfg.decision().minProb(), cfg.decision().minMargin()),
                trades(sub(pB, rows), d, rows, cfg.decision().minProb(), cfg.decision().minMargin()));
        List<Sweep> sweep = new ArrayList<>();
        for (double[] th : SWEEP) {
            sweep.add(new Sweep(th[0], th[1], trades(sub(pA, rows), d, rows, th[0], th[1]),
                    trades(sub(pB, rows), d, rows, th[0], th[1])));
        }
        oosOut.add(new Oos(h, d, foldOf, foldMonth, pA, pB));
        folds.sort(java.util.Comparator.comparing(FoldResult::testMonth));
        return new HorizonResult(h, d.size(), tests.get(0).toString(), tests.get(tests.size() - 1).toString(),
                lockboxFrom.toString(), lockboxTo.toString(), folds, s, sweep, average(impA), average(impB),
                (System.nanoTime() - t0) / 1e9);
    }

    private record FoldOutput(int fold, int[] testRows, double[][] pA, double[][] pB, double[][] pBase,
                              Map<String, Double> impA, Map<String, Double> impB, FoldResult result) {
    }

    private FoldOutput runFold(int fold, YearMonth test, YearMonth lockboxFrom, Dataset d, int[] colsA, int[] colsB,
                               List<String> namesA, List<String> namesB) {
        long testStart = Dataset.epoch(test.atDay(1));
        YearMonth endMonth = test.plusMonths(cfg.testMonths());
        if (endMonth.isAfter(lockboxFrom)) endMonth = lockboxFrom;
        long testEnd = Dataset.epoch(endMonth.atDay(1));
        long trainStart = Math.max(Dataset.epoch(test.minusMonths(cfg.trainMonths()).atDay(1)), Dataset.epoch(cfg.from()));
        long knownBy = testStart - cfg.embargoDays() * 86_400L;

        List<Integer> train = new ArrayList<>(), testRows = new ArrayList<>();
        for (int i = 0; i < d.size(); i++) {
            long m = d.moment[i];
            if (m >= trainStart && m < testStart && d.labelAvailable[i] <= knownBy) train.add(i);
            else if (m >= testStart && m < testEnd) testRows.add(i);
        }
        int[] tr = train.stream().mapToInt(Integer::intValue).toArray();
        int[] te = testRows.stream().mapToInt(Integer::intValue).toArray();
        int[] yTrain = sub(d.y, tr);
        int[] yTest = sub(d.y, te);

        long seed = cfg.seed() + fold;
        Gbm a = Gbm.fit(matrix(d, tr, colsA), yTrain, namesA, cfg.gbm(), seed);
        Gbm b = Gbm.fit(matrix(d, tr, colsB), yTrain, namesB, cfg.gbm(), seed);
        double[][] pA = a.predict(matrix(d, te, colsA));
        double[][] pB = b.predict(matrix(d, te, colsB));
        double[][] pBase = Metrics.priors(yTrain, te.length);

        int events = 0;
        for (int i : te) if (d.event[i]) events++;
        FoldResult r = new FoldResult(test.toString(), tr.length, te.length, events,
                Metrics.logLoss(pBase, yTest), Metrics.logLoss(pA, yTest), Metrics.logLoss(pB, yTest),
                Metrics.auc(pA, yTest, Dataset.UP), Metrics.auc(pB, yTest, Dataset.UP),
                trades(pA, d, te, cfg.decision().minProb(), cfg.decision().minMargin()),
                trades(pB, d, te, cfg.decision().minProb(), cfg.decision().minMargin()));
        log.debug("fold {} pronto", test);
        return new FoldOutput(fold, te, pA, pB, pBase, a.importance(), b.importance(), r);
    }

    private Metrics.Trades trades(double[][] p, Dataset d, int[] rows, double minProb, double minMargin) {
        return Metrics.trades(p, subD(d.yBuy, rows), subD(d.ySell, rows), minProb, minMargin, cfg.stopAtr(),
                cfg.riskPerTradePct());
    }

    private static double[][] matrix(Dataset d, int[] rows, int[] cols) {
        double[][] m = new double[rows.length][cols.length];
        for (int r = 0; r < rows.length; r++) {
            double[] src = d.x[rows[r]];
            for (int c = 0; c < cols.length; c++) m[r][c] = src[cols[c]];
        }
        return m;
    }

    private static double[][] sub(double[][] a, int[] rows) {
        double[][] out = new double[rows.length][];
        for (int i = 0; i < rows.length; i++) out[i] = a[rows[i]];
        return out;
    }

    private static int[] sub(int[] a, int[] rows) {
        int[] out = new int[rows.length];
        for (int i = 0; i < rows.length; i++) out[i] = a[rows[i]];
        return out;
    }

    private static double[] subD(double[] a, int[] rows) {
        double[] out = new double[rows.length];
        for (int i = 0; i < rows.length; i++) out[i] = a[rows[i]];
        return out;
    }

    private static List<String> concat(List<String> a, List<String> b) {
        List<String> out = new ArrayList<>(a);
        out.addAll(b);
        return out;
    }

    /** Importância média nos folds, da maior para a menor. */
    private static Map<String, Double> average(List<Map<String, Double>> maps) {
        Map<String, Double> sum = new LinkedHashMap<>();
        for (Map<String, Double> m : maps) m.forEach((k, v) -> sum.merge(k, v / maps.size(), Double::sum));
        Map<String, Double> sorted = new LinkedHashMap<>();
        sum.entrySet().stream().sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .forEach(e -> sorted.put(e.getKey(), e.getValue()));
        return sorted;
    }
}
