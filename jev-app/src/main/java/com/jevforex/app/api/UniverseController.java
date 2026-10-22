package com.jevforex.app.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jevforex.app.live.PredictionRepository;
import com.jevforex.app.live.ScannerService;
import com.jevforex.app.trade.OrderService;
import com.jevforex.features.scan.Scanner;
import com.jevforex.lake.LakeStorage;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

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
 * Universo de um símbolo (página Universo, pedido do operador em 22/10/2026): tudo o que o sistema sabe sobre ele, com o
 * tempo como eixo. O planeta é o símbolo (preço, movimento do dia, RSI); as luas, as previsões; os asteroides, as
 * divulgações do calendário das moedas dele; os cometas, as notícias recentes; as estrelas, os alertas do radar.
 *
 * <pre>
 * GET /api/universe?symbol=XAUUSD&mode=present   divulgações da próxima hora
 * GET /api/universe?symbol=XAUUSD&mode=future    de 1 h a 7 dias
 * GET /api/universe?symbol=XAUUSD&mode=past      últimas 48 h, com o que aconteceu (placar das previsões de evento)
 * </pre>
 *
 * A "inclinação" de um evento futuro NÃO é previsão do número: é o lado esperado pelo consenso (previsto × anterior)
 * combinado com o que esse evento fez com este símbolo no passado (mapa de eventos). Sem histórico = neutro.
 */
@RestController
@Profile("!cli")
@RequestMapping("/api/universe")
public class UniverseController {

    private final JdbcClient jdbc;
    private final LakeStorage lake;
    private final ObjectMapper mapper;
    private final ScannerService scanner;
    private final PredictionRepository predictions;
    private final OrderService orders;
    private final Map<String, Object[]> eventMapCache = new HashMap<>();   // mercado → {mtime, mapa}

    public UniverseController(JdbcClient jdbc, LakeStorage lake, ObjectMapper mapper, ScannerService scanner,
                              PredictionRepository predictions, OrderService orders) {
        this.jdbc = jdbc;
        this.lake = lake;
        this.mapper = mapper;
        this.scanner = scanner;
        this.predictions = predictions;
        this.orders = orders;
    }

    @GetMapping
    public Map<String, Object> universe(@RequestParam String symbol, @RequestParam(defaultValue = "present") String mode) {
        String sym = symbol.trim().toUpperCase(java.util.Locale.ROOT);
        String market = orders.marketOf(sym);
        List<String> currencies = orders.currenciesOf(sym);
        Instant now = Instant.now();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("symbol", sym);
        out.put("market", market);
        out.put("currencies", currencies);
        out.put("mode", mode);
        out.put("now", now);
        out.put("planet", planet(market, sym));
        out.put("moons", predictions.latest(market).stream().filter(p -> p.symbol().equals(sym)).toList());
        out.put("comets", comets(currencies, now));

        Map<String, JsonNode> history = eventMap(market, sym);
        Instant from, to;
        switch (mode) {
            case "future" -> { from = now.plus(Duration.ofHours(1)); to = now.plus(Duration.ofDays(7)); }
            case "past" -> { from = now.minus(Duration.ofHours(48)); to = now; }
            default -> { from = now; to = now.plus(Duration.ofHours(1)); }
        }
        List<Map<String, Object>> asteroids = new ArrayList<>();
        if (!currencies.isEmpty()) {
            for (Map<String, Object> e : events(currencies, from, to)) {
                JsonNode h = history.get((String) e.get("event_code"));
                e.put("history", h == null ? null : historyOf(h));
                e.put("lean", lean(e, h));
                if ("past".equals(mode)) e.put("outcome", outcome(market, sym, (Instant) e.get("at")));
                asteroids.add(e);
            }
        }
        out.put("asteroids", asteroids);
        return out;
    }

    // ------------------------------------------------------------------ o planeta e as estrelas (radar)

    private Map<String, Object> planet(String market, String symbol) {
        Map<String, Object> p = new LinkedHashMap<>();
        try {
            for (Scanner.Result r : scanner.scan(market).symbols()) {
                if (!r.symbol().equals(symbol)) continue;
                p.put("price", r.price());
                p.put("last_bar", r.lastBarUtc());
                p.put("moves", r.moves());
                p.put("day", r.day());
                r.tfs().stream().filter(t -> t.tf().equals("M15")).findFirst().ifPresent(t -> {
                    p.put("rsi_m15", t.rsiNow());
                    p.put("atr_m15", t.atr());
                    p.put("pct_b_m15", t.pctB());
                });
                p.put("stars", r.alerts());   // os alertas do radar viram estrelas
            }
        } catch (RuntimeException e) {
            p.put("error", "radar indisponível: " + e.getMessage());
        }
        return p;
    }

    // ------------------------------------------------------------------ os cometas (notícias recentes)

    private List<Map<String, Object>> comets(List<String> currencies, Instant now) {
        if (currencies.isEmpty()) return List.of();
        return jdbc.sql("""
                        SELECT issuer, currency, title, url, coalesce(published_at, first_seen_at) AS at
                          FROM raw_document
                         WHERE parent_id IS NULL AND doc_type = 'cb_text' AND currency IN (:cur)
                           AND coalesce(feed_id, '') NOT LIKE '%-archive'
                           AND coalesce(published_at, first_seen_at) >= :from
                         ORDER BY at DESC LIMIT 12
                        """)
                .param("cur", currencies).param("from", Timestamp.from(now.minus(Duration.ofHours(48))))
                .query((rs, i) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("issuer", rs.getString("issuer"));
                    m.put("currency", rs.getString("currency"));
                    m.put("title", rs.getString("title"));
                    m.put("url", rs.getString("url"));
                    m.put("at", rs.getTimestamp("at").toInstant());
                    return m;
                }).list();
    }

    // ------------------------------------------------------------------ os asteroides (calendário)

    private List<Map<String, Object>> events(List<String> currencies, Instant from, Instant to) {
        return jdbc.sql("""
                        SELECT v.event_code, coalesce(d.name, v.event_code) AS name, v.currency, v.importance,
                               v.scheduled_at, v.actual, v.forecast, v.previous
                          FROM (SELECT DISTINCT ON (mt5_value_id) *
                                  FROM calendar_event
                                 WHERE scheduled_at >= :from AND scheduled_at < :to AND currency IN (:cur)
                                   AND importance IN ('HIGH', 'MODERATE', 'LOW')
                                 ORDER BY mt5_value_id, first_seen_at DESC) v
                          LEFT JOIN calendar_event_def d ON d.mt5_event_id = v.mt5_event_id
                         ORDER BY v.scheduled_at
                        """)
                .param("from", Timestamp.from(from)).param("to", Timestamp.from(to)).param("cur", currencies)
                .query((rs, i) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("event_code", rs.getString("event_code"));
                    m.put("name", rs.getString("name"));
                    m.put("currency", rs.getString("currency"));
                    m.put("importance", rs.getString("importance"));
                    m.put("at", rs.getTimestamp("scheduled_at").toInstant());
                    m.put("actual", rs.getObject("actual"));
                    m.put("forecast", rs.getObject("forecast"));
                    m.put("previous", rs.getObject("previous"));
                    return m;
                }).list();
    }

    private static Map<String, Object> historyOf(JsonNode h) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (String f : List.of("n", "nPos", "nNeg", "abs15", "abs60", "upPos15", "upNeg15", "movPos15", "movNeg15", "spreadAtr")) {
            JsonNode v = h.get(f);
            m.put(f, v == null || v.isNull() ? null : v.isInt() ? v.asInt() : v.asDouble());
        }
        return m;
    }

    /**
     * Inclinação de um evento para ESTE símbolo: o lado esperado pelo consenso (previsto acima/abaixo do anterior) e o
     * que o evento fez com o símbolo nesse cenário (P de subir de +2 a +15 min). Sem previsto, sem histórico ou sem
     * padrão (entre 40% e 60%) = neutro.
     */
    static Map<String, Object> lean(Map<String, Object> e, JsonNode h) {
        Map<String, Object> out = new LinkedHashMap<>();
        Double fc = num(e.get("forecast")), prev = num(e.get("previous"));
        if (h == null) return lean(out, "neutral", null, "sem histórico deste evento neste símbolo (mapa de eventos)");
        if (fc == null || prev == null || fc.equals(prev)) {
            return lean(out, "neutral", null, "sem previsão do consenso (ou igual ao anterior): o lado depende do número");
        }
        boolean higher = fc > prev;
        JsonNode up = h.get(higher ? "upPos15" : "upNeg15");
        JsonNode n = h.get(higher ? "nPos" : "nNeg");
        if (up == null || up.isNull() || n == null || n.asInt() < 8) {
            return lean(out, "neutral", null, "poucos casos no histórico com o número " + (higher ? "acima" : "abaixo"));
        }
        double p = up.asDouble();
        String why = String.format(java.util.Locale.ROOT, "consenso espera número %s do anterior; quando veio %s, o símbolo subiu "
                + "em %.0f%% de %d casos (de +2 a +15 min)", higher ? "acima" : "abaixo", higher ? "acima do previsto" : "abaixo do previsto",
                p * 100, n.asInt());
        return lean(out, p >= 0.6 ? "up" : p <= 0.4 ? "down" : "neutral", p, why);
    }

    private static Map<String, Object> lean(Map<String, Object> m, String dir, Double pUp, String why) {
        m.put("dir", dir);
        m.put("p_up", pUp);
        m.put("why", why);
        return m;
    }

    /** O que aconteceu depois de uma divulgação: as previsões de evento do símbolo (B) e o resultado delas. */
    private Map<String, Object> outcome(String market, String symbol, Instant at) {
        Map<String, Object> out = new LinkedHashMap<>();
        jdbc.sql("""
                        SELECT horizon_min, p_up, p_down, p_flat, label FROM prediction
                         WHERE market = :m AND symbol = :s AND kind = 'EVENT' AND model = 'B'
                           AND moment_utc BETWEEN :a AND :b ORDER BY moment_utc
                        """)
                .param("m", market).param("s", symbol).param("a", Timestamp.from(at))
                .param("b", Timestamp.from(at.plus(Duration.ofMinutes(10))))
                .query((rs, i) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    String label = rs.getString("label");
                    double up = rs.getDouble("p_up"), down = rs.getDouble("p_down"), flat = rs.getDouble("p_flat");
                    String said = flat >= Math.max(up, down) ? "LATERAL" : up > down ? "ALTA" : "QUEDA";
                    m.put("label", label);
                    m.put("said", said);
                    m.put("hit", label == null ? null : label.equals(said));
                    out.put(rs.getInt("horizon_min") + "m", m);
                    return null;
                }).list();
        return out;
    }

    // ------------------------------------------------------------------ mapa de eventos (relatório do events-map)

    private synchronized Map<String, JsonNode> eventMap(String market, String symbol) {
        Path f = lake.root().resolve("reports/events/market=" + market).resolve("latest.json");
        Map<String, JsonNode> rows = new HashMap<>();
        try {
            if (!Files.exists(f)) return rows;
            long mtime = Files.getLastModifiedTime(f).toMillis();
            Object[] cached = eventMapCache.get(market);
            JsonNode report;
            if (cached != null && (long) cached[0] == mtime) report = (JsonNode) cached[1];
            else {
                report = mapper.readTree(f.toFile());
                eventMapCache.put(market, new Object[]{mtime, report});
            }
            for (JsonNode r : report.path("rows")) {
                if (symbol.equals(r.path("symbol").asText())) rows.put(r.path("eventCode").asText(), r);
            }
        } catch (Exception e) {
            // sem mapa: os asteroides ficam neutros
        }
        return rows;
    }

    private static Double num(Object o) {
        return o instanceof Number n ? n.doubleValue() : null;
    }
}
