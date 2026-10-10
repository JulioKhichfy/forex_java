package com.jevforex.app.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jevforex.app.config.FeatureProperties;
import com.jevforex.app.config.SilverProperties;
import com.jevforex.lake.LakeSql;
import com.jevforex.lake.LakeStorage;
import com.jevforex.ml.ModelStore;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Dados do dashboard além das previsões (passo 6b). Só leitura.
 *
 * <pre>
 * GET /api/calendar/upcoming?hours=24   eventos com peso (HIGH/MODERATE) das últimas 2 h às próximas N h
 * GET /api/news?limit=30                últimos documentos dos bancos centrais + leitura do Jev (se já avaliados)
 * GET /api/models                       versão em produção, versões, cofre e walk-forward de referência
 * </pre>
 */
@RestController
@Profile("!cli")
@RequestMapping("/api")
public class DashboardController {

    private final JdbcClient jdbc;
    private final LakeStorage lake;
    private final FeatureProperties featureProps;
    private final SilverProperties silver;
    private final ObjectMapper mapper;

    public DashboardController(JdbcClient jdbc, LakeStorage lake, FeatureProperties featureProps,
                               SilverProperties silver, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.lake = lake;
        this.featureProps = featureProps;
        this.silver = silver;
        this.mapper = mapper;
    }

    @GetMapping("/calendar/upcoming")
    public List<Map<String, Object>> upcoming(@RequestParam(defaultValue = "24") int hours) {
        List<String> important = featureProps.toConfig().importanceWeights().entrySet().stream()
                .filter(e -> e.getValue() > 0).map(Map.Entry::getKey).toList();
        return jdbc.sql("""
                        SELECT v.mt5_value_id, v.event_code, coalesce(d.name, v.event_code) AS name, v.currency,
                               v.importance, v.scheduled_at, v.actual, v.forecast, v.previous, v.origin
                          FROM (SELECT DISTINCT ON (mt5_value_id) *
                                  FROM calendar_event
                                 WHERE scheduled_at BETWEEN :from AND :to AND importance IN (:imp)
                                 ORDER BY mt5_value_id, first_seen_at DESC) v
                          LEFT JOIN calendar_event_def d ON d.mt5_event_id = v.mt5_event_id
                         ORDER BY v.scheduled_at, v.currency
                        """)
                .param("from", Timestamp.from(Instant.now().minus(Duration.ofHours(2))))
                .param("to", Timestamp.from(Instant.now().plus(Duration.ofHours(Math.min(hours, 24 * 14)))))
                .param("imp", important)
                .query((rs, i) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("value_id", rs.getLong("mt5_value_id"));
                    m.put("event_code", rs.getString("event_code"));
                    m.put("name", rs.getString("name"));
                    m.put("currency", rs.getString("currency"));
                    m.put("importance", rs.getString("importance"));
                    m.put("scheduled_at", rs.getTimestamp("scheduled_at").toInstant());
                    m.put("actual", rs.getObject("actual"));
                    m.put("forecast", rs.getObject("forecast"));
                    m.put("previous", rs.getObject("previous"));
                    return m;
                }).list();
    }

    @GetMapping("/news")
    public List<Map<String, Object>> news(@RequestParam(defaultValue = "30") int limit) {
        List<Map<String, Object>> docs = jdbc.sql("""
                        SELECT id, source, feed_id, issuer, currency, title, url, published_at, first_seen_at
                          FROM raw_document
                         WHERE parent_id IS NULL AND doc_type = 'cb_text'
                         ORDER BY coalesce(published_at, first_seen_at) DESC, id DESC LIMIT :limit
                        """)
                .param("limit", Math.min(limit, 200))
                .query((rs, i) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", rs.getLong("id"));
                    m.put("issuer", rs.getString("issuer"));
                    m.put("currency", rs.getString("currency"));
                    m.put("feed", rs.getString("feed_id"));
                    m.put("title", rs.getString("title"));
                    m.put("url", rs.getString("url"));
                    m.put("published_at", rs.getTimestamp("published_at") == null ? null
                            : rs.getTimestamp("published_at").toInstant());
                    m.put("first_seen_at", rs.getTimestamp("first_seen_at").toInstant());
                    return m;
                }).list();
        Map<String, Map<String, Object>> jev = jevReadings();
        for (Map<String, Object> d : docs) d.put("jev", jev.get((String) d.get("url")));
        return docs;
    }

    /** Leitura do Jev por URL (gold/currency_signals do conjunto em uso): tom, sinal e relevância. */
    private Map<String, Map<String, Object>> jevReadings() {
        var text = featureProps.toConfig().text();
        if (text == null) return Map.of();
        Path signals = lake.root().resolve(text.signalsPath());
        if (!Files.isDirectory(signals)) return Map.of();
        Map<String, Map<String, Object>> out = new HashMap<>();
        try (LakeSql sql = LakeSql.open(lake.root().resolve("tmp").resolve("duckdb-api"), "256MB")) {
            sql.query("""
                    SELECT url, stance, signal, relevance, guidance_change, chunks
                      FROM read_parquet('%s/**/*.parquet', hive_partitioning = true)
                     WHERE available_utc >= now() - INTERVAL 120 DAY
                    """.formatted(LakeSql.slashes(signals)), rs -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("qset", text.qset());
                m.put("stance", rs.getDouble("stance"));
                m.put("signal", rs.getDouble("signal"));
                m.put("relevance", rs.getDouble("relevance"));
                m.put("guidance_change", rs.getObject("guidance_change"));
                m.put("chunks", rs.getLong("chunks"));
                out.put(rs.getString("url"), m);
                return null;
            });
        }
        return out;
    }

    @GetMapping("/models")
    public Map<String, Object> models() throws IOException {
        ModelStore store = new ModelStore(lake.root());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("champion", store.champion().orElse(null));
        List<Map<String, Object>> versions = new ArrayList<>();
        for (String v : store.versions()) {
            ModelStore.Manifest mf = store.manifest(v);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("version", v);
            m.put("trained_at", mf.trainedAt());
            m.put("train_from", mf.trainFrom());
            m.put("train_to", mf.trainTo());
            m.put("lockbox_run", mf.lockboxRun());
            m.put("entries", mf.entries().stream().map(e -> Map.of("model", e.model(), "horizon", e.horizon(),
                    "rows", e.rows(), "features", e.features().size())).toList());
            versions.add(m);
        }
        out.put("versions", versions);
        Path lock = lake.root().resolve("reports/lockbox/OPENED.json");
        JsonNode lockInfo = Files.exists(lock) ? mapper.readTree(lock.toFile()) : null;
        out.put("lockbox", lockInfo);
        if (lockInfo != null && lockInfo.hasNonNull("run")) {
            out.put("lockbox_summary", summary(lake.root().resolve("reports/lockbox").resolve(lockInfo.get("run").asText())));
        }
        Path wf = lake.root().resolve("reports/walkforward");
        if (Files.isDirectory(wf)) {
            try (var s = Files.list(wf)) {
                String last = s.map(p -> p.getFileName().toString()).sorted().reduce((a, b) -> b).orElse(null);
                if (last != null) {
                    out.put("walkforward_run", last);
                    out.put("walkforward_summary", summary(wf.resolve(last)));
                }
            }
        }
        return out;
    }

    /** Resumo de um metrics.json do experimento: por horizonte e modelo, log loss e operações. */
    private List<Map<String, Object>> summary(Path dir) throws IOException {
        Path f = dir.resolve("metrics.json");
        if (!Files.exists(f)) return List.of();
        JsonNode root = mapper.readTree(f.toFile());
        List<Map<String, Object>> out = new ArrayList<>();
        for (JsonNode h : root.path("horizons")) {
            JsonNode s = h.path("summary");
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("horizon", h.path("horizon").asInt());
            m.put("first_test", h.path("firstTest").asText());
            m.put("last_test", h.path("lastTest").asText());
            m.put("folds", s.path("folds").asInt());
            m.put("rows", s.path("rows").asInt());
            m.put("ll_base", s.path("llBase").asDouble());
            m.put("models", s.path("models"));
            m.put("comparisons", s.path("comparisons"));
            m.put("sweep", h.path("sweep"));
            out.add(m);
        }
        return out;
    }
}
