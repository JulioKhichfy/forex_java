package com.jevforex.ml;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.jevforex.lake.LakeSql;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * Modelos de produção no lake (documento mestre, capítulo 11: "cada modelo salvo com features.json, metrics.json"):
 *
 * <pre>
 * models/market=fx/&lt;versão&gt;/&lt;modelo&gt;-&lt;h&gt;.ser   modelo treinado (Smile)
 * models/market=fx/&lt;versão&gt;/manifest.json          features na ordem exata, janela de treino, parâmetros
 * models/market=fx/champion.json                    versão em uso (só muda por promoção)
 * </pre>
 * O modelo novo (challenger) só substitui o atual (champion) por promoção explícita.
 */
public final class ModelStore {

    private static final ObjectMapper JSON = new ObjectMapper().registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private final Path dir;

    public ModelStore(Path lakeRoot) {
        this(lakeRoot, "fx");
    }

    public ModelStore(Path lakeRoot, String market) {
        if (market == null || !market.matches("[a-z]+")) throw new IllegalArgumentException("mercado inválido: " + market);
        this.dir = lakeRoot.resolve("models/market=" + market);
    }

    /** Um modelo de um horizonte. */
    public record Entry(String model, int horizon, String file, List<String> features, int rows,
                        double inSampleLogLoss) {
    }

    /**
     * @param trainFrom  primeiro momento da janela de treino
     * @param trainTo    último momento com label conhecido
     * @param lockboxRun execução que abriu o cofre (estimativa honesta), se houver
     * @param walkForwardRun walk-forward de referência, se houver
     */
    public record Manifest(String version, String fset, Instant trainedAt, String trainFrom, String trainTo,
                           ExperimentConfig.Gbm gbm, List<String> classes, List<Entry> entries, String lockboxRun,
                           String walkForwardRun) {
    }

    public record Champion(String version, Instant promotedAt, String note) {
    }

    /** Um conjunto carregado: modelo → horizonte → Gbm. */
    public record Loaded(Manifest manifest, Map<String, Map<Integer, Gbm>> models) {
        public Gbm get(String model, int horizon) {
            Map<Integer, Gbm> m = models.get(model);
            return m == null ? null : m.get(horizon);
        }
    }

    /**
     * Um modelo × horizonte no último mês completo.
     *
     * @param llChampion   log loss do modelo em produção (NaN se ele não tem este modelo/horizonte)
     * @param llChallenger log loss da receita nova treinada só até o início do mês
     */
    public record ChallengeRow(String model, int horizon, int rows, double llChampion, double llChallenger,
                               Metrics.Trades tradesChampion, Metrics.Trades tradesChallenger) {
    }

    /**
     * Champion × challenger (documento mestre, cap. 11): promoção só se o challenger vencer no último mês e não
     * piorar o drawdown. A decisão é do operador; isto é a recomendação.
     *
     * @param championOutOfSample o champion foi treinado antes do mês avaliado (senão a comparação não vale)
     * @param mainModel           o modelo que decide a recomendação (eventos + preço, 60 min)
     */
    public record Challenge(String month, String championVersion, boolean championOutOfSample, String mainModel,
                            List<ChallengeRow> rows, boolean recommended, String reason) {
    }

    public Challenge challenge(String version) {
        Path f = dir.resolve(version).resolve("challenge.json");
        if (!Files.exists(f)) return null;
        try {
            return JSON.readValue(f.toFile(), Challenge.class);
        } catch (IOException e) {
            throw new UncheckedIOException("challenge.json ilegível em " + version, e);
        }
    }

    /**
     * Treina os modelos de produção com os últimos {@code trainMonths} meses de labels CONHECIDOS (inclui o cofre:
     * só depois de aberto) e grava uma versão nova. Não promove. Se já há modelo em produção, compara os dois no
     * último mês completo e grava challenge.json.
     */
    public Manifest train(LakeSql sql, Path lakeRoot, ExperimentConfig cfg, String lockboxRun, String walkForwardRun,
                          Consumer<String> progress) {
        Loaded champ = champion().map(c -> load(c.version())).orElse(null);
        List<ChallengeRow> challengeRows = new ArrayList<>();
        String month = null;
        String version = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC).format(Instant.now());
        Path out = dir.resolve(version);
        try {
            Files.createDirectories(out);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        List<Entry> entries = new ArrayList<>();
        String from = null, to = null;
        for (int h : cfg.horizons()) {
            Dataset d = Dataset.load(sql, lakeRoot, cfg.market(), cfg.fset(), h, cfg.from(), cfg.exits());
            long last = Arrays.stream(d.labelAvailable).max().orElseThrow();
            long start = Dataset.epoch(LocalDate.ofInstant(Instant.ofEpochSecond(last), ZoneOffset.UTC)
                    .minusMonths(cfg.trainMonths()));
            int[] rows = java.util.stream.IntStream.range(0, d.size())
                    .filter(i -> d.moment[i] >= start && d.labelAvailable[i] <= last).toArray();
            from = Instant.ofEpochSecond(d.moment[rows[0]]).toString();
            to = Instant.ofEpochSecond(d.moment[rows[rows.length - 1]]).toString();
            int[] y = new int[rows.length];
            for (int k = 0; k < rows.length; k++) y[k] = d.y[rows[k]];
            if (champ != null) {
                java.time.YearMonth ym = java.time.YearMonth.from(Instant.ofEpochSecond(last).atZone(ZoneOffset.UTC))
                        .minusMonths(1);
                month = ym.toString();
                challengeRows.addAll(challengeHorizon(d, h, ym, champ, cfg, progress));
            }
            for (String m : cfg.models()) {
                int[] cols = d.columnsOf(Dataset.MODELS.get(m));
                double[][] x = new double[rows.length][cols.length];
                for (int k = 0; k < rows.length; k++) {
                    for (int c = 0; c < cols.length; c++) x[k][c] = d.x[rows[k]][cols[c]];
                }
                Gbm g = Gbm.fit(x, y, d.names(cols), cfg.gbm(), cfg.seed());
                String file = m + "-" + h + ".ser";
                g.save(out.resolve(file));
                double ll = Metrics.logLoss(g.predict(x), y);
                entries.add(new Entry(m, h, file, g.names(), rows.length, ll));
                progress.accept(String.format(java.util.Locale.ROOT, "  %s · %d min · %d linhas (%s a %s) · log loss no "
                        + "treino %.4f", m, h, rows.length, from.substring(0, 10), to.substring(0, 10), ll));
            }
        }
        Manifest mf = new Manifest(version, cfg.fset(), Instant.now(), from, to, cfg.gbm(), Dataset.CLASSES, entries,
                lockboxRun, walkForwardRun);
        write(out.resolve("manifest.json"), mf);
        if (champ != null) {
            write(out.resolve("challenge.json"), verdict(month, champ, challengeRows));
        }
        return mf;
    }

    /** Champion e receita nova num mês: a receita treina com os {@code trainMonths} meses antes dele (com embargo). */
    private static List<ChallengeRow> challengeHorizon(Dataset d, int h, java.time.YearMonth ym, Loaded champ,
                                                       ExperimentConfig cfg, Consumer<String> progress) {
        long testStart = Dataset.epoch(ym.atDay(1)), testEnd = Dataset.epoch(ym.plusMonths(1).atDay(1));
        long trainStart = Dataset.epoch(ym.minusMonths(cfg.trainMonths()).atDay(1));
        long knownBy = testStart - cfg.embargoDays() * 86_400L;
        int[] test = java.util.stream.IntStream.range(0, d.size())
                .filter(i -> d.moment[i] >= testStart && d.moment[i] < testEnd).toArray();
        int[] train = java.util.stream.IntStream.range(0, d.size())
                .filter(i -> d.moment[i] >= trainStart && d.moment[i] < testStart && d.labelAvailable[i] <= knownBy)
                .toArray();
        List<ChallengeRow> out = new ArrayList<>();
        if (test.length == 0 || train.length == 0) return out;
        int[] yTest = new int[test.length], yTrain = new int[train.length];
        for (int k = 0; k < test.length; k++) yTest[k] = d.y[test[k]];
        for (int k = 0; k < train.length; k++) yTrain[k] = d.y[train[k]];
        double[] rb = new double[test.length], rs = new double[test.length];
        for (int k = 0; k < test.length; k++) {
            rb[k] = d.rBuy[test[k]];
            rs[k] = d.rSell[test[k]];
        }
        for (String m : cfg.models()) {
            int[] cols = d.columnsOf(Dataset.MODELS.get(m));
            Gbm recipe = Gbm.fit(matrix(d, train, cols), yTrain, d.names(cols), cfg.gbm(), cfg.seed());
            double[][] pNew = recipe.predict(matrix(d, test, cols));
            double llNew = Metrics.logLoss(pNew, yTest);
            Metrics.Trades tNew = Metrics.trades(pNew, rb, rs, null, cfg.decision().minProb(),
                    cfg.decision().minMargin(), cfg.riskPerTradePct());
            Gbm old = champ.get(m, h);
            double llOld = Double.NaN;
            Metrics.Trades tOld = null;
            if (old != null) {
                int[] oc = byName(d, old.names());
                if (oc != null) {
                    double[][] pOld = old.predict(matrix(d, test, oc));
                    llOld = Metrics.logLoss(pOld, yTest);
                    tOld = Metrics.trades(pOld, rb, rs, null, cfg.decision().minProb(), cfg.decision().minMargin(),
                            cfg.riskPerTradePct());
                }
            }
            out.add(new ChallengeRow(m, h, test.length, llOld, llNew, tOld, tNew));
            progress.accept(String.format(java.util.Locale.ROOT, "  desafio %s · %d min · %s: em produção %.4f × nova %.4f",
                    m, h, ym, llOld, llNew));
        }
        return out;
    }

    private static Challenge verdict(String month, Loaded champ, List<ChallengeRow> rows) {
        String main = "B";
        boolean oos = champ.manifest().trainTo() != null && month != null
                && champ.manifest().trainTo().compareTo(month + "-01") < 0;
        ChallengeRow r = rows.stream().filter(x -> x.model().equals(main) && x.horizon() == 60).findFirst()
                .orElse(rows.isEmpty() ? null : rows.get(0));
        if (r == null) return new Challenge(month, champ.manifest().version(), oos, main, rows, false, "sem dados do mês");
        if (!oos) {
            return new Challenge(month, champ.manifest().version(), false, r.model(), rows, false,
                    "o modelo em produção já viu " + month + " no treino: a comparação não vale; promova só se mudou "
                            + "algo na configuração e você conferiu o walk-forward");
        }
        boolean better = !Double.isNaN(r.llChampion()) && r.llChallenger() < r.llChampion();
        double ddOld = r.tradesChampion() == null ? 0 : r.tradesChampion().maxDrawdownPct();
        double ddNew = r.tradesChallenger() == null ? 0 : r.tradesChallenger().maxDrawdownPct();
        boolean ddOk = ddNew <= ddOld + 1e-9;
        String reason = String.format(java.util.Locale.ROOT, "%s %d min em %s: log loss em produção %.4f × nova %.4f; "
                + "drawdown %.1f%% × %.1f%%", r.model(), r.horizon(), month, r.llChampion(), r.llChallenger(), ddOld, ddNew);
        return new Challenge(month, champ.manifest().version(), true, r.model(), rows, better && ddOk,
                (better && ddOk ? "promover: " : "manter a atual: ") + reason);
    }

    private static double[][] matrix(Dataset d, int[] rows, int[] cols) {
        double[][] x = new double[rows.length][cols.length];
        for (int k = 0; k < rows.length; k++) {
            for (int c = 0; c < cols.length; c++) x[k][c] = d.x[rows[k]][cols[c]];
        }
        return x;
    }

    /** Colunas do Dataset na ordem de um modelo antigo; null se falta alguma (fset diferente). */
    private static int[] byName(Dataset d, List<String> names) {
        int[] out = new int[names.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = d.columns.indexOf(names.get(i));
            if (out[i] < 0) return null;
        }
        return out;
    }

    public List<String> versions() {
        if (!Files.isDirectory(dir)) return List.of();
        try (var s = Files.list(dir)) {
            return s.filter(p -> Files.exists(p.resolve("manifest.json"))).map(p -> p.getFileName().toString())
                    .sorted().toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public Manifest manifest(String version) {
        try {
            return JSON.readValue(dir.resolve(version).resolve("manifest.json").toFile(), Manifest.class);
        } catch (IOException e) {
            throw new UncheckedIOException("Versão de modelo ilegível: " + version, e);
        }
    }

    public Optional<Champion> champion() {
        Path f = dir.resolve("champion.json");
        if (!Files.exists(f)) return Optional.empty();
        try {
            return Optional.of(JSON.readValue(f.toFile(), Champion.class));
        } catch (IOException e) {
            throw new UncheckedIOException("champion.json ilegível", e);
        }
    }

    /** Coloca uma versão em uso. A anterior continua no disco (dá para voltar). */
    public Champion promote(String version, String note) {
        if (!versions().contains(version)) throw new IllegalArgumentException("Versão inexistente: " + version);
        Champion c = new Champion(version, Instant.now(), note);
        write(dir.resolve("champion.json"), c);
        return c;
    }

    public Loaded load(String version) {
        Manifest mf = manifest(version);
        Map<String, Map<Integer, Gbm>> models = new LinkedHashMap<>();
        for (Entry e : mf.entries()) {
            Gbm g = Gbm.load(dir.resolve(version).resolve(e.file()));
            if (!g.names().equals(e.features())) {
                throw new IllegalStateException("Modelo " + e.file() + " não bate com o manifest (ordem das features)");
            }
            models.computeIfAbsent(e.model(), k -> new LinkedHashMap<>()).put(e.horizon(), g);
        }
        return new Loaded(mf, models);
    }

    private static void write(Path file, Object value) {
        try {
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            JSON.writerWithDefaultPrettyPrinter().writeValue(tmp.toFile(), value);
            Files.move(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new UncheckedIOException("Não consegui gravar " + file, e);
        }
    }
}
