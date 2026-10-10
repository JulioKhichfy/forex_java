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
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Consumer;
import java.util.stream.IntStream;

/**
 * Experimento A × B × C com walk-forward, embargo e cofre (documento mestre, capítulo 11).
 *
 * <ul>
 *   <li>Fold: treina com {@code trainMonths} meses, testa no mês seguinte; só entram no treino linhas cujo
 *       label já era conhecido {@code embargoDays} antes do início do teste.</li>
 *   <li>Cofre: os últimos {@code lockboxMonths} meses completos ficam fora de tudo aqui.</li>
 *   <li>Todos os modelos são treinados e avaliados nas MESMAS linhas: B − A mede o calendário; C − B, o Jev.</li>
 * </ul>
 */
public final class ExperimentRunner {

    private static final Logger log = LoggerFactory.getLogger(ExperimentRunner.class);
    /** Limiares extras para ver a sensibilidade das operações simuladas. */
    private static final double[][] SWEEP = {{0.45, 0.15}, {0.50, 0.25}, {0.55, 0.30}, {0.60, 0.35}};

    private final Path lakeRoot;
    private final ExperimentConfig cfg;
    private final boolean lockbox;

    public ExperimentRunner(Path lakeRoot, ExperimentConfig cfg) {
        this(lakeRoot, cfg, false);
    }

    /**
     * @param lockbox true = ABRE O COFRE: os folds são os meses do cofre (cada um treina só com o que era conhecido
     *                antes dele). Decisão do usuário, uma única vez — quem chama garante a trava.
     */
    public ExperimentRunner(Path lakeRoot, ExperimentConfig cfg, boolean lockbox) {
        this.lakeRoot = lakeRoot;
        this.cfg = cfg;
        this.lockbox = lockbox;
    }

    /** Métricas de um modelo num fold. */
    public record FoldModel(double ll, double aucUp, Metrics.Trades trades) {
    }

    public record FoldResult(String testMonth, int nTrain, int nTest, int nTestEvents, double llBase,
                             Map<String, FoldModel> models) {
    }

    /**
     * Métricas de um modelo em TODAS as linhas fora da amostra; *Event = só momentos de evento.
     * Operações (gate 4, stop/alvo/tempo): trades = base; tradesLate = entrada +lateMinutes; tradesCost = custos ×
     * costStress (robustez do capítulo 11).
     */
    public record ModelSummary(double ll, double llEvent, double aucUp, double aucDown, double aucUpEvent,
                               Metrics.Trades trades, Metrics.Trades tradesLate, Metrics.Trades tradesCost) {
    }

    /**
     * @param comparisons "B×A" → em quantos folds B teve log loss menor que A (e assim por diante)
     */
    public record Summary(int folds, int rows, int eventRows, double llBase, double llEventBase,
                          Map<String, ModelSummary> models, Map<String, Integer> comparisons) {
    }

    public record Sweep(double minProb, double minMargin, Map<String, Metrics.Trades> models) {
    }

    public record HorizonResult(int horizon, int rows, List<String> models, String firstTest, String lastTest,
                                String lockboxFrom, String lockboxTo, List<FoldResult> folds, Summary summary,
                                List<Sweep> sweep, Map<String, Map<String, Double>> importance, double seconds) {
    }

    /**
     * @param report      relatório HTML (gravado ANTES das previsões: uma falha nelas não perde o resultado)
     * @param predictions pasta das previsões no gold; null se a gravação falhou (motivo em predictionsError)
     */
    public record Result(String runId, ExperimentConfig config, List<HorizonResult> horizons, Path report,
                         Path predictions, String predictionsError, boolean lockbox) {
    }

    /** Previsões fora da amostra de um horizonte (para gravar no gold). */
    record Oos(int horizon, Dataset data, int[] foldOf, String[] foldMonth, Map<String, double[][]> probs) {
    }

    public Result run(LakeSql sql, Consumer<String> progress) throws Exception {
        String runId = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC).format(Instant.now());
        List<HorizonResult> out = new ArrayList<>();
        List<Oos> oos = new ArrayList<>();
        for (int h : cfg.horizons()) {
            long t0 = System.nanoTime();
            Dataset d = Dataset.load(sql, lakeRoot, cfg.fset(), h, cfg.from(), cfg.exits());
            List<String> models = new ArrayList<>(cfg.models());
            if (!d.hasText && models.remove("C")) {
                progress.accept("ATENÇÃO: fset " + cfg.fset() + " sem o grupo C (texto do Jev): modelo C fica de fora");
            }
            if (!d.hasTone && models.remove("D")) {
                progress.accept("ATENÇÃO: fset " + cfg.fset() + " sem a surpresa de tom: modelo D fica de fora");
            }
            progress.accept(String.format("Horizonte %d min: %d linhas carregadas · modelos %s", h, d.size(), models));
            out.add(runHorizon(h, d, models, oos, progress, t0));
        }
        Path report = ReportWriter.write(lakeRoot, new Result(runId, cfg, out, null, null, null, lockbox));
        progress.accept("Relatório gravado: " + report);
        try {
            Path preds = Predictions.write(sql, lakeRoot, cfg.fset(), runId, oos);
            return new Result(runId, cfg, out, report, preds, null, lockbox);
        } catch (RuntimeException e) {
            log.warn("Previsões não gravadas (o relatório está salvo): {}", e.getMessage());
            return new Result(runId, cfg, out, report, null, e.getMessage(), lockbox);
        }
    }

    private HorizonResult runHorizon(int h, Dataset d, List<String> models, List<Oos> oosOut, Consumer<String> progress,
                                     long t0) throws Exception {
        // ---------------------------------------------------------------- folds e cofre
        YearMonth lastIncomplete = YearMonth.from(Instant.ofEpochSecond(d.moment[d.size() - 1]).atZone(ZoneOffset.UTC));
        YearMonth lockboxTo = lastIncomplete.minusMonths(1);
        YearMonth lockboxFrom = lockboxTo.minusMonths(cfg.lockboxMonths() - 1L);
        YearMonth firstTest = YearMonth.from(cfg.from()).plusMonths(cfg.trainMonths());
        List<YearMonth> tests = new ArrayList<>();
        if (lockbox) {
            for (YearMonth m = lockboxFrom; !m.isAfter(lockboxTo); m = m.plusMonths(cfg.testMonths())) tests.add(m);
        } else {
            for (YearMonth m = firstTest; m.isBefore(lockboxFrom); m = m.plusMonths(cfg.testMonths())) tests.add(m);
        }
        YearMonth testLimit = lockbox ? lockboxTo.plusMonths(1) : lockboxFrom;
        if (tests.isEmpty()) throw new IllegalStateException("Dados insuficientes para um fold (treino de "
                + cfg.trainMonths() + " meses + cofre de " + cfg.lockboxMonths() + ")");

        Map<String, int[]> cols = new LinkedHashMap<>();
        Map<String, List<String>> names = new LinkedHashMap<>();
        for (String m : models) {
            int[] c = d.columnsOf(Dataset.MODELS.get(m));
            cols.put(m, c);
            names.put(m, d.names(c));
        }

        Map<String, double[][]> probs = new LinkedHashMap<>();
        models.forEach(m -> probs.put(m, new double[d.size()][]));
        double[][] pBase = new double[d.size()][];
        int[] foldOf = new int[d.size()];
        Arrays.fill(foldOf, -1);
        String[] foldMonth = new String[tests.size()];
        Map<String, List<Map<String, Double>>> imps = new LinkedHashMap<>();
        models.forEach(m -> imps.put(m, new ArrayList<>()));
        List<FoldResult> folds = new ArrayList<>();

        ExecutorService pool = Executors.newFixedThreadPool(cfg.threads());
        try {
            List<Future<FoldOutput>> futures = new ArrayList<>();
            for (int f = 0; f < tests.size(); f++) {
                final int fold = f;
                YearMonth m = tests.get(f);
                foldMonth[f] = m.toString();
                futures.add(pool.submit(() -> runFold(fold, m, testLimit, d, models, cols, names)));
            }
            int done = 0;
            for (Future<FoldOutput> fu : futures) {
                FoldOutput o = fu.get();
                for (int k = 0; k < o.testRows.length; k++) {
                    int i = o.testRows[k];
                    for (String m : models) probs.get(m)[i] = o.probs.get(m)[k];
                    pBase[i] = o.pBase[k];
                    foldOf[i] = o.fold;
                }
                models.forEach(m -> imps.get(m).add(o.importance.get(m)));
                folds.add(o.result);
                StringBuilder ll = new StringBuilder();
                o.result.models().forEach((m, fm) -> ll.append(String.format(Locale.ROOT, " %s %.4f", m, fm.ll())));
                progress.accept(String.format(Locale.ROOT, "  %d min · fold %s (%d/%d): treino %d, teste %d · log loss%s",
                        h, o.result.testMonth(), ++done, tests.size(), o.result.nTrain(), o.result.nTest(), ll));
            }
        } finally {
            pool.shutdownNow();
        }

        // ---------------------------------------------------------------- todas as linhas fora da amostra
        int[] rows = IntStream.range(0, d.size()).filter(i -> foldOf[i] >= 0).toArray();
        int[] evRows = Arrays.stream(rows).filter(i -> d.event[i]).toArray();
        int[] y = sub(d.y, rows), yEv = sub(d.y, evRows);
        Map<String, ModelSummary> summaries = new LinkedHashMap<>();
        for (String m : models) {
            double[][] p = sub(probs.get(m), rows), pEv = sub(probs.get(m), evRows);
            summaries.put(m, new ModelSummary(Metrics.logLoss(p, y), Metrics.logLoss(pEv, yEv),
                    Metrics.auc(p, y, Dataset.UP), Metrics.auc(p, y, Dataset.DOWN), Metrics.auc(pEv, yEv, Dataset.UP),
                    trades(p, d, rows, cfg.decision().minProb(), cfg.decision().minMargin()),
                    Metrics.trades(p, subD(d.rBuyLate, rows), subD(d.rSellLate, rows), null, cfg.decision().minProb(),
                            cfg.decision().minMargin(), cfg.riskPerTradePct()),
                    Metrics.trades(p, subD(d.rBuy, rows), subD(d.rSell, rows), extraCost(d, rows),
                            cfg.decision().minProb(), cfg.decision().minMargin(), cfg.riskPerTradePct())));
        }
        Map<String, Integer> comparisons = new LinkedHashMap<>();
        for (int a = 0; a < models.size(); a++) {
            for (int b = a + 1; b < models.size(); b++) {
                String lo = models.get(a), hi = models.get(b);
                comparisons.put(hi + "×" + lo, (int) folds.stream()
                        .filter(f -> f.models().get(hi).ll() < f.models().get(lo).ll()).count());
            }
        }
        Summary s = new Summary(folds.size(), rows.length, evRows.length, Metrics.logLoss(sub(pBase, rows), y),
                Metrics.logLoss(sub(pBase, evRows), yEv), summaries, comparisons);

        List<Sweep> sweep = new ArrayList<>();
        for (double[] th : SWEEP) {
            Map<String, Metrics.Trades> t = new LinkedHashMap<>();
            for (String m : models) t.put(m, trades(sub(probs.get(m), rows), d, rows, th[0], th[1]));
            sweep.add(new Sweep(th[0], th[1], t));
        }
        oosOut.add(new Oos(h, d, foldOf, foldMonth, probs));
        folds.sort(Comparator.comparing(FoldResult::testMonth));
        Map<String, Map<String, Double>> importance = new LinkedHashMap<>();
        models.forEach(m -> importance.put(m, average(imps.get(m))));
        return new HorizonResult(h, d.size(), models, tests.get(0).toString(), tests.get(tests.size() - 1).toString(),
                lockboxFrom.toString(), lockboxTo.toString(), folds, s, sweep, importance,
                (System.nanoTime() - t0) / 1e9);
    }

    private record FoldOutput(int fold, int[] testRows, Map<String, double[][]> probs, double[][] pBase,
                              Map<String, Map<String, Double>> importance, FoldResult result) {
    }

    /** @param testLimit primeiro mês que o teste nunca alcança (início do cofre; no modo cofre, o mês depois dele) */
    private FoldOutput runFold(int fold, YearMonth test, YearMonth testLimit, Dataset d, List<String> models,
                               Map<String, int[]> cols, Map<String, List<String>> names) {
        long testStart = Dataset.epoch(test.atDay(1));
        YearMonth endMonth = test.plusMonths(cfg.testMonths());
        if (endMonth.isAfter(testLimit)) endMonth = testLimit;
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
        int[] yTrain = sub(d.y, tr), yTest = sub(d.y, te);

        long seed = cfg.seed() + fold;
        Map<String, double[][]> probs = new LinkedHashMap<>();
        Map<String, Map<String, Double>> importance = new LinkedHashMap<>();
        Map<String, FoldModel> fm = new LinkedHashMap<>();
        for (String m : models) {
            Gbm g = Gbm.fit(matrix(d, tr, cols.get(m)), yTrain, names.get(m), cfg.gbm(), seed);
            double[][] p = g.predict(matrix(d, te, cols.get(m)));
            probs.put(m, p);
            importance.put(m, g.importance());
            fm.put(m, new FoldModel(Metrics.logLoss(p, yTest), Metrics.auc(p, yTest, Dataset.UP),
                    trades(p, d, te, cfg.decision().minProb(), cfg.decision().minMargin())));
        }
        double[][] pBase = Metrics.priors(yTrain, te.length);
        int events = 0;
        for (int i : te) if (d.event[i]) events++;
        FoldResult r = new FoldResult(test.toString(), tr.length, te.length, events, Metrics.logLoss(pBase, yTest), fm);
        log.debug("fold {} pronto", test);
        return new FoldOutput(fold, te, probs, pBase, importance, r);
    }

    private Metrics.Trades trades(double[][] p, Dataset d, int[] rows, double minProb, double minMargin) {
        return Metrics.trades(p, subD(d.rBuy, rows), subD(d.rSell, rows), null, minProb, minMargin,
                cfg.riskPerTradePct());
    }

    /** Custo extra por operação no teste de estresse: (costStress − 1) × custo de 1 spread, em R. */
    private double[] extraCost(Dataset d, int[] rows) {
        double[] out = subD(d.costR, rows);
        for (int i = 0; i < out.length; i++) out[i] *= cfg.costStress() - 1;
        return out;
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
