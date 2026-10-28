package com.jevforex.app.trade;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jevforex.app.config.RiskSettingsHolder;
import com.jevforex.core.Instrument;
import com.jevforex.lake.LakeStorage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Página "Principal de hoje" (pedido do operador em 28/10/2026): os eventos de importância ALTA das próximas horas de
 * todas as moedas, o que esperar de cada um e um plano por evento:
 * <ul>
 *   <li><b>MODEL</b> — espera a previsão do modelo B feita depois da divulgação (evento + 2 min) e entra no lado mais
 *       provável de cada símbolo (pula LATERAL). É o caminho que teve vantagem no ouro e nos índices.</li>
 *   <li><b>SURPRISE</b> — espera o resultado: positivo para a moeda (o "impacto" do MT5) = a favor da moeda. No estudo
 *       12d deu −0,15 ATR por operação (o preço já andou antes de entrarmos).</li>
 *   <li><b>NOW</b> — entra já, antes da divulgação, no lado que o consenso implica (previsto × anterior e se "maior é
 *       bom" para a moeda, aprendido do histórico). Não testado.</li>
 * </ul>
 * Ordens sem as travas de risco por ordem ({@link OrderService#CASINO_WAIVED}, decisão do operador), com stop e alvo em
 * US$ e saída por tempo opcional; valem as técnicas e a perda do dia/semana.
 */
@Service
@Profile("!cli")
public class EventDeskService {

    private static final Logger log = LoggerFactory.getLogger(EventDeskService.class);
    private static final Set<String> CURRENCIES = Set.of("USD", "EUR", "GBP", "JPY", "CHF", "AUD", "CAD", "NZD");
    /** Sem o resultado até aqui depois do horário, o plano expira (o calendário do MT5 pode atrasar ou falhar). */
    static final Duration SURPRISE_WAIT = Duration.ofMinutes(30);
    /** A previsão de evento sai em evento + 2 min; até aqui, o plano espera por ela. */
    static final Duration MODEL_WAIT = Duration.ofMinutes(15);

    private final ObjectProvider<JdbcClient> jdbc;
    private final OrderService orders;
    private final RiskSettingsHolder riskHolder;
    private final LakeStorage lake;
    private final ObjectMapper mapper;
    private final ForexFactoryCalendar forexFactory;
    private Map<String, Integer> goodSigns = Map.of();
    private Instant goodSignsAt = Instant.EPOCH;

    public EventDeskService(ObjectProvider<JdbcClient> jdbc, OrderService orders, RiskSettingsHolder riskHolder,
                            LakeStorage lake, ObjectMapper mapper, ForexFactoryCalendar forexFactory) {
        this.jdbc = jdbc;
        this.orders = orders;
        this.riskHolder = riskHolder;
        this.lake = lake;
        this.mapper = mapper;
        this.forexFactory = forexFactory;
    }

    // ------------------------------------------------------------------ regras de direção (puras, testáveis)

    /**
     * Para que lado o símbolo anda quando a moeda se fortalece: +1 (forex com a moeda na base), −1 (moeda cotada; ouro,
     * prata, bitcoin e ETH contra o dólar), 0 = sem regra (índices, energia, ações, ou a moeda não está no símbolo).
     */
    static int dirOf(String symbol, String market, String currency) {
        if ("fx".equals(market) && symbol.length() == 6) {
            if (symbol.startsWith(currency)) return 1;
            if (symbol.endsWith(currency)) return -1;
            return 0;
        }
        if (("metals".equals(market) || "crypto".equals(market)) && "USD".equals(currency) && symbol.endsWith("USD")) {
            return -1;
        }
        return 0;
    }

    /** O lado do consenso para a moeda: +1 favorável, −1 desfavorável, 0 = igual ao anterior, null = sem como saber. */
    static Integer consensus(Double forecast, Double previous, Integer goodSign) {
        if (forecast == null || previous == null || goodSign == null) return null;
        int s = Double.compare(forecast, previous);
        return s == 0 ? 0 : s * goodSign;
    }

    /** O lado do resultado para a moeda: o impacto do MT5; sem ele, real × previsto e "maior é bom". */
    static Integer surpriseDir(String impact, Double actual, Double forecast, Integer goodSign) {
        if ("POSITIVE".equals(impact)) return 1;
        if ("NEGATIVE".equals(impact)) return -1;
        if (actual == null || forecast == null || goodSign == null) return null;
        int s = Double.compare(actual, forecast);
        return s == 0 ? 0 : s * goodSign;
    }

    static String side(int currencyDir, int symbolDir) {
        return currencyDir * symbolDir > 0 ? "BUY" : "SELL";
    }

    /** "Maior é bom" para decisões de juros sem histórico de impacto: juro maior favorece a moeda. */
    static Integer goodSignOrRate(Integer learned, String code, String title) {
        if (learned != null) return learned;
        boolean rate = (code != null && code.contains("interest-rate")) || (title != null && title.matches("(?i).*\\brate$")
                && !title.toLowerCase(Locale.ROOT).contains("unemployment"));
        return rate ? 1 : null;
    }

    /**
     * O consenso do Forex Factory para um horário (mesma moeda, mesmo minuto): o item com previsto e anterior numéricos;
     * nas decisões de juros, o da taxa (ex.: "Overnight Rate", "Federal Funds Rate").
     */
    static ForexFactoryCalendar.Item ffMatch(List<ForexFactoryCalendar.Item> items, String currency, Instant at, boolean rate) {
        ForexFactoryCalendar.Item best = null;
        for (ForexFactoryCalendar.Item i : items) {
            if (!currency.equals(i.currency()) || Math.abs(Duration.between(i.at(), at).toSeconds()) > 60) continue;
            if (ForexFactoryCalendar.number(i.forecast()) == null || ForexFactoryCalendar.number(i.previous()) == null) continue;
            boolean isRate = i.title().matches("(?i).*\\brate$");
            if (rate && isRate) return i;
            // fora dos juros, só os de importância alta (o minuto costuma ter vários dados menores)
            if (best == null && "High".equals(i.impact())) best = i;
        }
        return best;
    }

    // ------------------------------------------------------------------ o que a página mostra

    public Map<String, Object> view(int hoursBack, int hoursAhead) {
        Instant now = Instant.now();
        Map<String, Integer> good = goodSigns();
        Map<String, JsonNode> history = history();
        Map<String, List<String>> byMarket = orders.symbolsByMarket();
        List<Map<String, Object>> rows = jdbc.getObject().sql("""
                        SELECT v.event_code, coalesce(d.name, v.event_code) AS name, v.currency, v.importance,
                               v.scheduled_at, v.actual, v.forecast, v.previous, v.impact
                          FROM (SELECT DISTINCT ON (mt5_value_id) *
                                  FROM calendar_event
                                 WHERE scheduled_at >= :from AND scheduled_at < :to AND importance = 'HIGH'
                                 ORDER BY mt5_value_id, first_seen_at DESC) v
                          LEFT JOIN calendar_event_def d ON d.mt5_event_id = v.mt5_event_id
                         ORDER BY v.scheduled_at, v.currency, v.event_code
                        """)
                .param("from", Timestamp.from(now.minus(Duration.ofHours(Math.min(hoursBack, 48)))))
                .param("to", Timestamp.from(now.plus(Duration.ofHours(Math.min(hoursAhead, 168)))))
                .query().listOfRows();
        Map<String, Map<String, Object>> clusters = new LinkedHashMap<>();
        for (Map<String, Object> r : rows) {
            String cur = (String) r.get("currency");
            if (!CURRENCIES.contains(cur)) continue;
            Instant at = ((Timestamp) r.get("scheduled_at")).toInstant();
            String key = cur + "@" + at;
            Map<String, Object> c = clusters.computeIfAbsent(key, k -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("key", k);
                m.put("currency", cur);
                m.put("at", at);
                m.put("events", new ArrayList<Map<String, Object>>());
                return m;
            });
            Map<String, Object> e = new LinkedHashMap<>();
            String code = (String) r.get("event_code");
            e.put("code", code);
            e.put("name", r.get("name"));
            e.put("actual", r.get("actual"));
            e.put("forecast", r.get("forecast"));
            e.put("previous", r.get("previous"));
            e.put("impact", r.get("impact"));
            e.put("good_sign", good.get(code));
            e.put("consensus", consensus(num(r.get("forecast")), num(r.get("previous")), good.get(code)));
            @SuppressWarnings("unchecked") List<Map<String, Object>> evs = (List<Map<String, Object>>) c.get("events");
            if (evs.stream().noneMatch(x -> code.equals(x.get("code")))) evs.add(e);
        }
        for (Map<String, Object> c : clusters.values()) {
            @SuppressWarnings("unchecked") List<Map<String, Object>> evs = (List<Map<String, Object>>) c.get("events");
            Map<String, Object> main = mainEvent(evs);
            c.put("main", main.get("code"));
            // consenso do Forex Factory: o MT5 não traz o previsto das decisões de juros
            String mainCode = (String) main.get("code");
            ForexFactoryCalendar.Item ff = ffMatch(ff(), (String) c.get("currency"), (Instant) c.get("at"),
                    mainCode.contains("interest-rate"));
            if (ff != null) {
                Integer gs = goodSignOrRate(good.get(mainCode), mainCode, ff.title());
                Integer dir = consensus(ForexFactoryCalendar.number(ff.forecast()), ForexFactoryCalendar.number(ff.previous()), gs);
                Map<String, Object> fm = new LinkedHashMap<>();
                fm.put("title", ff.title());
                fm.put("forecast", ff.forecast());
                fm.put("previous", ff.previous());
                fm.put("dir", dir);
                c.put("ff", fm);
                if (main.get("consensus") == null && dir != null) {
                    main.put("consensus", dir);
                    main.put("consensus_source", "Forex Factory: " + ff.title() + " previsto " + ff.forecast()
                            + " × anterior " + ff.previous());
                }
            }
            c.put("released", evs.stream().anyMatch(e -> e.get("actual") != null));
            String cur = (String) c.get("currency");
            List<Map<String, Object>> syms = new ArrayList<>();
            for (var m : byMarket.entrySet()) {
                for (String s : m.getValue()) {
                    int d = dirOf(s, m.getKey(), cur);
                    boolean affected = d != 0 || orders.currenciesOf(s).contains(cur);
                    if (!affected) continue;
                    Map<String, Object> sm = new LinkedHashMap<>();
                    sm.put("symbol", s);
                    sm.put("market", m.getKey());
                    sm.put("dir", d);   // 0 = só o modo "modelo" sabe o lado
                    JsonNode h = history.get(main.get("code") + "|" + s);
                    if (h != null) {
                        for (String f : List.of("n", "nPos", "nNeg", "abs15", "abs60", "upPos15", "upNeg15", "spreadAtr")) {
                            JsonNode v = h.get(f);
                            sm.put(f, v == null || v.isNull() ? null : v.isInt() ? v.asInt() : v.asDouble());
                        }
                    }
                    syms.add(sm);
                }
            }
            c.put("symbols", syms);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("now", now);
        out.put("clusters", clusters.values());
        JdbcClient db = jdbc.getObject();
        out.put("plans", db.sql("""
                        SELECT id, currency, event_at, event_code, mode, symbols, lot, stop_usd, take_usd, close_after_minutes,
                               currency_side, status, message, created_at, done_at
                          FROM event_plan WHERE event_at >= :from OR status = 'ARMED' ORDER BY event_at, id
                        """).param("from", Timestamp.from(now.minus(Duration.ofHours(24)))).query().listOfRows());
        out.put("orders", db.sql("""
                        SELECT po.plan_id, po.symbol, po.side, po.status, po.message, po.created_at,
                               (SELECT sum(d.profit + d.swap + d.commission) FROM order_request o JOIN ea_deal d
                                    ON d.position_ticket = o.ticket WHERE o.id = po.order_id) AS result_usd
                          FROM event_plan_order po JOIN event_plan p ON p.id = po.plan_id
                         WHERE p.event_at >= :from ORDER BY po.id DESC
                        """).param("from", Timestamp.from(now.minus(Duration.ofHours(24)))).query().listOfRows());
        return out;
    }

    /** O evento que decide: o de juros, se houver; senão o primeiro com previsto e anterior; senão o primeiro. */
    static Map<String, Object> mainEvent(List<Map<String, Object>> evs) {
        for (Map<String, Object> e : evs) if (String.valueOf(e.get("code")).contains("interest-rate-decision")) return e;
        for (Map<String, Object> e : evs) if (e.get("forecast") != null && e.get("previous") != null) return e;
        return evs.get(0);
    }

    // ------------------------------------------------------------------ planos

    /**
     * Cria um plano. MODEL e SURPRISE ficam armados até o evento; NOW entra na hora.
     *
     * @param currencySide só no NOW: +1 a favor da moeda, −1 contra (o consenso ou a escolha do operador)
     */
    public synchronized Map<String, Object> createPlan(String currency, Instant at, String eventCode, String mode,
                                                       List<String> symbols, Double lot, double stopUsd, double takeUsd,
                                                       Integer closeAfter, Integer currencySide) {
        if (!Set.of("MODEL", "SURPRISE", "NOW").contains(mode)) throw new IllegalArgumentException("modo: MODEL, SURPRISE ou NOW");
        if (symbols == null || symbols.isEmpty()) throw new IllegalArgumentException("escolha pelo menos um símbolo");
        if (!(stopUsd > 0) || !(takeUsd > 0)) throw new IllegalArgumentException("stop e alvo em US$ precisam ser > 0");
        if (lot != null && (!(lot > 0) || lot > riskHolder.get().orders().maxLot() + 1e-9)) {
            throw new IllegalArgumentException(String.format(Locale.ROOT,
                    "Lote %.2f fora do permitido (até %.2f, trading.risk.orders.max-lot)", lot, riskHolder.get().orders().maxLot()));
        }
        if (closeAfter != null && closeAfter < 1) closeAfter = null;
        if ("NOW".equals(mode) && (currencySide == null || currencySide == 0)) {
            throw new IllegalArgumentException("operar agora precisa de um lado (o do consenso ou o escolhido)");
        }
        if (!"NOW".equals(mode) && Instant.now().isAfter(at.plus(Duration.ofMinutes(5)))) {
            throw new IllegalArgumentException("o evento já passou: o modo automático é para eventos que ainda vão sair");
        }
        long id = jdbc.getObject().sql("""
                        INSERT INTO event_plan (currency, event_at, event_code, mode, symbols, lot, stop_usd, take_usd,
                                                close_after_minutes, currency_side, status, message)
                        VALUES (:c, :at, :code, :mode, :syms, :lot, :stop, :take, :ca, :cs, 'ARMED', :msg) RETURNING id
                        """)
                .param("c", currency).param("at", Timestamp.from(at)).param("code", eventCode).param("mode", mode)
                .param("syms", String.join(",", symbols)).param("lot", lot, Types.DOUBLE).param("stop", stopUsd)
                .param("take", takeUsd).param("ca", closeAfter, Types.INTEGER).param("cs", currencySide, Types.INTEGER)
                .param("msg", "NOW".equals(mode) ? "enviando" : "armado: espera o evento", Types.VARCHAR)
                .query(Long.class).single();
        log.info("Principal de hoje: plano {} {} {} {} em {} ({})", id, mode, currency, eventCode, at, String.join(",", symbols));
        if ("NOW".equals(mode)) {
            Plan p = plan(id);
            for (String s : p.symbols()) {
                int d = dirOf(s, orders.marketOf(s), currency);
                if (d == 0) {
                    record(p, s, null, null, "SKIPPED", "sem regra de direção para " + s + " (só o modo modelo)");
                    continue;
                }
                send(p, s, side(currencySide, d), currencySide > 0 ? "a favor de " + currency + " (consenso)" : "contra " + currency + " (consenso)");
            }
            finish(id, "DONE", "enviado antes do evento");
        }
        return Map.of("id", id);
    }

    public synchronized void cancel(long id) {
        jdbc.getObject().sql("UPDATE event_plan SET status = 'CANCELLED', message = 'cancelado pelo operador', done_at = now() "
                + "WHERE id = :id AND status = 'ARMED'").param("id", id).update();
    }

    record Plan(long id, String currency, Instant at, String eventCode, String mode, List<String> symbols, Double lot,
                double stopUsd, double takeUsd, Integer closeAfter) {
    }

    // ------------------------------------------------------------------ o laço

    @Scheduled(initialDelayString = "PT45S", fixedDelayString = "PT3S")
    public synchronized void tick() {
        List<Long> armed = jdbc.getObject().sql("SELECT id FROM event_plan WHERE status = 'ARMED' AND event_at <= now() ORDER BY id")
                .query(Long.class).list();
        for (long id : armed) {
            try {
                Plan p = plan(id);
                if ("SURPRISE".equals(p.mode())) runSurprise(p);
                else if ("MODEL".equals(p.mode())) runModel(p);
            } catch (RuntimeException e) {
                log.warn("Principal de hoje: plano {} — {}", id, e.toString());
            }
        }
    }

    private void runSurprise(Plan p) {
        Map<String, Object> e = jdbc.getObject().sql("""
                        SELECT actual, forecast, impact FROM calendar_event
                         WHERE currency = :c AND event_code = :code AND scheduled_at = :at AND actual IS NOT NULL
                         ORDER BY first_seen_at DESC LIMIT 1
                        """).param("c", p.currency()).param("code", p.eventCode()).param("at", Timestamp.from(p.at()))
                .query().listOfRows().stream().findFirst().orElse(null);
        if (e == null) {
            if (Instant.now().isAfter(p.at().plus(SURPRISE_WAIT))) {
                finish(p.id(), "EXPIRED", "o resultado não chegou em " + SURPRISE_WAIT.toMinutes() + " min (calendário do MT5)");
            }
            return;
        }
        Integer dir = surpriseDir((String) e.get("impact"), num(e.get("actual")), num(e.get("forecast")), goodSigns().get(p.eventCode()));
        if (dir == null || dir == 0) {
            finish(p.id(), "DONE", String.format(Locale.ROOT, "resultado %s (previsto %s): sem lado claro para %s, não entrou",
                    e.get("actual"), e.get("forecast"), p.currency()));
            return;
        }
        String why = String.format(Locale.ROOT, "resultado %s × previsto %s: %s para %s", e.get("actual"), e.get("forecast"),
                dir > 0 ? "positivo" : "negativo", p.currency());
        for (String s : p.symbols()) {
            int d = dirOf(s, orders.marketOf(s), p.currency());
            if (d == 0) {
                record(p, s, null, null, "SKIPPED", "sem regra de direção pelo dado para " + s);
                continue;
            }
            send(p, s, side(dir, d), why);
        }
        finish(p.id(), "DONE", why);
    }

    private void runModel(Plan p) {
        Set<String> done = new java.util.HashSet<>(jdbc.getObject().sql("SELECT symbol FROM event_plan_order WHERE plan_id = :p")
                .param("p", p.id()).query(String.class).list());
        for (String s : p.symbols()) {
            if (done.contains(s)) continue;
            Map<String, Object> pr = jdbc.getObject().sql("""
                            SELECT p_up, p_down, p_flat, horizon_min, moment_utc FROM prediction
                             WHERE symbol = :s AND kind = 'EVENT' AND model = 'B' AND horizon_min = 15
                               AND moment_utc BETWEEN :a AND :b ORDER BY moment_utc DESC LIMIT 1
                            """).param("s", s).param("a", Timestamp.from(p.at()))
                    .param("b", Timestamp.from(p.at().plus(Duration.ofMinutes(10)))).query().listOfRows().stream().findFirst().orElse(null);
            if (pr == null) continue;
            double up = num(pr.get("p_up")), dn = num(pr.get("p_down")), fl = num(pr.get("p_flat"));
            String probs = String.format(Locale.ROOT, "B 15 min depois do evento: alta %.0f%% · lateral %.0f%% · queda %.0f%%",
                    up * 100, fl * 100, dn * 100);
            if (fl >= Math.max(up, dn)) {
                record(p, s, null, null, "SKIPPED", probs + " → LATERAL, não entra");
                continue;
            }
            send(p, s, up >= dn ? "BUY" : "SELL", probs);
            done.add(s);
        }
        long handled = jdbc.getObject().sql("SELECT count(*) FROM event_plan_order WHERE plan_id = :p").param("p", p.id())
                .query(Long.class).single();
        if (handled >= p.symbols().size()) {
            finish(p.id(), "DONE", "previsões depois do evento usadas");
        } else if (Instant.now().isAfter(p.at().plus(MODEL_WAIT))) {
            for (String s : p.symbols()) {
                if (!done.contains(s)) record(p, s, null, null, "SKIPPED", "sem previsão do modelo depois do evento");
            }
            finish(p.id(), "DONE", "parte dos símbolos sem previsão depois do evento");
        }
    }

    private void send(Plan p, String symbol, String side, String why) {
        Instrument inst = orders.instrument(symbol);
        Double lot = p.lot() != null ? p.lot() : inst == null ? null : inst.minLot();
        OrderService.Submit r = orders.submit(symbol, side, lot,
                OrderService.Roulette.casino(p.closeAfter() == null ? 0 : p.closeAfter(), p.takeUsd(), p.stopUsd()));
        if (r.orderId() != null) {
            record(p, symbol, side, r.orderId(), "SENT", String.format(Locale.ROOT, "%s %.2f · alvo US$ %.2f · stop US$ %.2f · %s",
                    side, r.preview().lot(), p.takeUsd(), p.stopUsd(), why));
            log.info("Principal de hoje: plano {} {} {} (ordem {}) — {}", p.id(), side, symbol, r.orderId(), why);
            return;
        }
        String failed = r.preview().checks().stream().filter(x -> !x.ok()).map(x -> x.gate() + " (" + x.value() + ")")
                .reduce((a, b) -> a + "; " + b).orElse("?");
        record(p, symbol, side, null, "REFUSED", "recusada: " + failed);
    }

    private void record(Plan p, String symbol, String side, Long orderId, String status, String message) {
        jdbc.getObject().sql("""
                        INSERT INTO event_plan_order (plan_id, symbol, side, order_id, status, message)
                        VALUES (:p, :s, :side, :o, :st, :m) ON CONFLICT (plan_id, symbol) DO NOTHING
                        """).param("p", p.id()).param("s", symbol).param("side", side, Types.VARCHAR)
                .param("o", orderId, Types.BIGINT).param("st", status).param("m", message, Types.VARCHAR).update();
    }

    private void finish(long id, String status, String message) {
        jdbc.getObject().sql("UPDATE event_plan SET status = :st, message = :m, done_at = now() WHERE id = :id")
                .param("st", status).param("m", message, Types.VARCHAR).param("id", id).update();
    }

    private Plan plan(long id) {
        return jdbc.getObject().sql("SELECT * FROM event_plan WHERE id = :id").param("id", id)
                .query((rs, i) -> new Plan(rs.getLong("id"), rs.getString("currency"), rs.getTimestamp("event_at").toInstant(),
                        rs.getString("event_code"), rs.getString("mode"), List.of(rs.getString("symbols").split(",")),
                        (Double) rs.getObject("lot"), rs.getDouble("stop_usd"), rs.getDouble("take_usd"),
                        (Integer) rs.getObject("close_after_minutes"))).single();
    }

    // ------------------------------------------------------------------ apoio

    /**
     * "Maior é bom" para a moeda, por evento, aprendido do calendário: nas divulgações com impacto marcado pelo MT5, o
     * real acima do previsto foi POSITIVE (+1) ou NEGATIVE (−1, ex.: desemprego). Precisa de pelo menos 5 casos.
     */
    synchronized Map<String, Integer> goodSigns() {
        if (goodSignsAt.isAfter(Instant.now().minus(Duration.ofHours(6)))) return goodSigns;
        Map<String, Integer> m = new HashMap<>();
        jdbc.getObject().sql("""
                        SELECT event_code, avg(CASE WHEN (actual > forecast) = (impact = 'POSITIVE') THEN 1.0 ELSE 0.0 END) AS agree,
                               count(*) AS n
                          FROM calendar_event
                         WHERE impact IN ('POSITIVE', 'NEGATIVE') AND actual IS NOT NULL AND forecast IS NOT NULL
                           AND actual <> forecast
                         GROUP BY event_code HAVING count(*) >= 5
                        """).query().listOfRows().forEach(r -> {
            double a = ((Number) r.get("agree")).doubleValue();
            if (a >= 0.8) m.put((String) r.get("event_code"), 1);
            else if (a <= 0.2) m.put((String) r.get("event_code"), -1);
        });
        goodSigns = m;
        goodSignsAt = Instant.now();
        return m;
    }

    /** O mapa de eventos (comando events-map) de todos os mercados: evento|símbolo → histórico de reação. */
    private Map<String, JsonNode> history() {
        Map<String, JsonNode> out = new HashMap<>();
        Path dir = lake.root().resolve("reports/events");
        if (!Files.isDirectory(dir)) return out;
        try (var s = Files.list(dir)) {
            for (Path m : s.toList()) {
                Path f = m.resolve("latest.json");
                if (!Files.exists(f)) continue;
                for (JsonNode r : mapper.readTree(f.toFile()).path("rows")) {
                    out.put(r.path("eventCode").asText() + "|" + r.path("symbol").asText(), r);
                }
            }
        } catch (Exception e) {
            log.warn("Principal de hoje: mapa de eventos ilegível ({})", e.toString());
        }
        return out;
    }

    private List<ForexFactoryCalendar.Item> ff() {
        try {
            return forexFactory.items();
        } catch (RuntimeException e) {
            return List.of();
        }
    }

    private static Double num(Object o) {
        return o instanceof Number n ? n.doubleValue() : null;
    }
}
