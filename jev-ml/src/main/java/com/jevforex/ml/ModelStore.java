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
        this.dir = lakeRoot.resolve("models/market=fx");
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
     * Treina os modelos de produção com os últimos {@code trainMonths} meses de labels CONHECIDOS (inclui o cofre:
     * só depois de aberto) e grava uma versão nova. Não promove.
     */
    public Manifest train(LakeSql sql, Path lakeRoot, ExperimentConfig cfg, String lockboxRun, String walkForwardRun,
                          Consumer<String> progress) {
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
            Dataset d = Dataset.load(sql, lakeRoot, cfg.fset(), h, cfg.from(), cfg.exits());
            long last = Arrays.stream(d.labelAvailable).max().orElseThrow();
            long start = Dataset.epoch(LocalDate.ofInstant(Instant.ofEpochSecond(last), ZoneOffset.UTC)
                    .minusMonths(cfg.trainMonths()));
            int[] rows = java.util.stream.IntStream.range(0, d.size())
                    .filter(i -> d.moment[i] >= start && d.labelAvailable[i] <= last).toArray();
            from = Instant.ofEpochSecond(d.moment[rows[0]]).toString();
            to = Instant.ofEpochSecond(d.moment[rows[rows.length - 1]]).toString();
            int[] y = new int[rows.length];
            for (int k = 0; k < rows.length; k++) y[k] = d.y[rows[k]];
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
        return mf;
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
