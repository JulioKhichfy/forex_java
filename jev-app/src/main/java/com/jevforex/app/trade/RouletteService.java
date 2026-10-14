package com.jevforex.app.trade;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jevforex.app.config.RiskSettingsHolder;
import com.jevforex.app.live.PredictionRepository;
import com.jevforex.core.Instrument;
import com.jevforex.core.Market;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.sql.Types;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Roleta (pedido do operador, 14/10/2026): com um mercado ligado, cada previsão nova do modelo e horizonte escolhidos
 * vira uma ordem no lado mais provável de cada símbolo — pulando quando LATERAL é o mais provável — com lote mínimo
 * (ou o escolhido) e saída pelo horizonte (15 ou 60 min). Fica ligada até o operador parar, ou até o disjuntor de
 * perda do dia/semana parar todas. As travas de risco por ordem não bloqueiam ({@link OrderService.Roulette}).
 *
 * <p>No placar das previsões até 14/10, operar sempre o lado mais provável deu E[R] negativo no forex, nos índices e
 * nas ações; a página mostra esse número ao lado de cada botão.</p>
 */
@Service
@Profile("!cli")
public class RouletteService {

    private static final Logger log = LoggerFactory.getLogger(RouletteService.class);
    private static final String KEY = "roulette.";
    /** Previsão mais velha que isto não vira ordem (entrar atrasado leva a vantagem do momento embora). */
    static final Duration ENTRY_MAX_AGE = Duration.ofMinutes(10);
    static final Set<Integer> HORIZONS = Set.of(15, 60);
    static final Set<String> MODELS = Set.of("A", "E", "B");

    private final TradeRepository repo;
    private final OrderService orders;
    private final PredictionRepository predictions;
    private final RiskSettingsHolder riskHolder;
    private final ObjectProvider<JdbcClient> jdbc;
    private final ObjectMapper mapper;

    public RouletteService(TradeRepository repo, OrderService orders, PredictionRepository predictions,
                           RiskSettingsHolder riskHolder, ObjectProvider<JdbcClient> jdbc, ObjectMapper mapper) {
        this.repo = repo;
        this.orders = orders;
        this.predictions = predictions;
        this.riskHolder = riskHolder;
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    /**
     * Estado de um mercado (app_setting roulette.&lt;mercado&gt;).
     *
     * @param lot        null = o lote mínimo de cada símbolo
     * @param stopReason por que parou (botão, disjuntor de perda)
     */
    public record Config(boolean on, int horizon, String model, Double lot, Instant startedAt, Instant stoppedAt,
                         String stopReason) {
    }

    public static List<String> markets() {
        return java.util.Arrays.stream(Market.values()).map(Market::code).toList();
    }

    public Config config(String market) {
        return repo.setting(KEY + market).map(this::parse)
                .orElse(new Config(false, 60, "B", null, null, null, null));
    }

    public synchronized Config start(String market, int horizon, String model, Double lot) {
        String m = Market.fromCode(market).code();
        if (!HORIZONS.contains(horizon)) throw new IllegalArgumentException("Horizonte deve ser 15 ou 60 min");
        if (!MODELS.contains(model)) throw new IllegalArgumentException("Modelo deve ser A, E ou B");
        if (lot != null && (!(lot > 0) || lot > riskHolder.get().orders().maxLot() + 1e-9)) {
            throw new IllegalArgumentException(String.format(Locale.ROOT,
                    "Lote %.2f fora do permitido (até %.2f, trading.risk.orders.max-lot)", lot,
                    riskHolder.get().orders().maxLot()));
        }
        Config c = new Config(true, horizon, model, lot, Instant.now(), null, null);
        save(m, c);
        log.info("Roleta {} LIGADA: modelo {}, {} min, lote {}", m, model, horizon, lot == null ? "mínimo" : lot);
        return c;
    }

    public synchronized Config stop(String market, String reason) {
        String m = Market.fromCode(market).code();
        Config c = config(m);
        if (!c.on()) return c;
        Config off = new Config(false, c.horizon(), c.model(), c.lot(), c.startedAt(), Instant.now(), reason);
        save(m, off);
        log.info("Roleta {} parada: {}", m, reason);
        return off;
    }

    public synchronized void stopAll(String reason) {
        for (String m : markets()) stop(m, reason);
    }

    // ------------------------------------------------------------------ o laço

    @Scheduled(initialDelayString = "PT40S", fixedDelayString = "PT10S")
    public synchronized void tick() {
        for (String market : markets()) {
            Config c = config(market);
            if (!c.on()) continue;
            try {
                run(market, c);
            } catch (RuntimeException e) {
                log.warn("Roleta {}: {}", market, e.toString());
            }
        }
    }

    private void run(String market, Config c) {
        Instant now = Instant.now();
        for (PredictionRepository.Prediction p : predictions.latest(market)) {
            if (!p.model().equals(c.model()) || p.horizonMin() != c.horizon()) continue;
            if (p.momentUtc().isBefore(now.minus(ENTRY_MAX_AGE))) continue;
            Long entry = claim(market, p);   // uma vez por previsão, mesmo se o servidor cair no meio
            if (entry == null) continue;
            Decision d = decide(p);
            if (d.side() == null) {
                finish(entry, null, d.pSide(), null, "SKIPPED", d.why());
                continue;
            }
            Instrument inst = orders.instrument(p.symbol());
            Double lot = c.lot() != null ? c.lot() : inst == null ? null : inst.minLot();
            OrderService.Submit s = orders.submit(p.symbol(), d.side(), lot,
                    new OrderService.Roulette(c.horizon(), p.id()));
            if (s.orderId() != null) {
                finish(entry, d.side(), d.pSide(), s.orderId(), "SENT", String.format(Locale.ROOT,
                        "%s %.2f · perda no stop US$ %.2f · fecha em %d min", d.side(), s.preview().lot(),
                        s.preview().riskUsd() == null ? 0 : s.preview().riskUsd(), c.horizon()));
                log.info("Roleta {}: {} {} {} (ordem {})", market, d.side(), s.preview().lot(), p.symbol(), s.orderId());
                continue;
            }
            List<String> failed = s.preview().checks().stream().filter(x -> !x.ok())
                    .map(x -> x.gate() + " (" + x.value() + ")").toList();
            finish(entry, d.side(), d.pSide(), null, "REFUSED", String.join("; ", failed));
            boolean breaker = s.preview().checks().stream().anyMatch(x -> !x.ok() && x.gate().startsWith("Perda d"));
            if (breaker) {
                stopAll("disjuntor de perda: " + String.join("; ", failed));
                return;
            }
        }
    }

    /** Lado escolhido (null = pular) e a probabilidade dele. */
    record Decision(String side, double pSide, String why) {
    }

    /** O lado mais provável entre alta e queda; pula quando LATERAL é o mais provável (escolha do operador). */
    static Decision decide(PredictionRepository.Prediction p) {
        double top = Math.max(p.pUp(), p.pDown());
        if (p.pFlat() >= top) {
            return new Decision(null, p.pFlat(), String.format(Locale.ROOT,
                    "LATERAL é o mais provável (%.0f%% × alta %.0f%% · queda %.0f%%)", 100 * p.pFlat(), 100 * p.pUp(),
                    100 * p.pDown()));
        }
        return p.pUp() > p.pDown() ? new Decision("BUY", p.pUp(), null) : new Decision("SELL", p.pDown(), null);
    }

    private Long claim(String market, PredictionRepository.Prediction p) {
        return jdbc.getObject().sql("""
                        INSERT INTO roulette_entry (market, prediction_id, symbol, model, horizon_min, status, message)
                        VALUES (:m, :p, :s, :model, :h, 'SENDING', 'conferindo as travas')
                        ON CONFLICT (prediction_id) DO NOTHING RETURNING id
                        """)
                .param("m", market).param("p", p.id()).param("s", p.symbol()).param("model", p.model())
                .param("h", p.horizonMin()).query(Long.class).optional().orElse(null);
    }

    private void finish(long id, String side, double pSide, Long orderId, String status, String message) {
        jdbc.getObject().sql("""
                        UPDATE roulette_entry SET side = :side, p_side = :p, order_id = :o, status = :st, message = :msg
                         WHERE id = :id
                        """)
                .param("side", side, Types.VARCHAR).param("p", pSide).param("o", orderId, Types.BIGINT)
                .param("st", status).param("msg", message, Types.VARCHAR).param("id", id).update();
    }

    // ------------------------------------------------------------------ o que a página mostra

    /** Estado, últimas entradas, resultado real das ordens da roleta e o que as previsões teriam dado. */
    public Map<String, Object> state(int days) {
        Instant from = Instant.now().minus(Duration.ofDays(Math.min(days, 365)));
        Map<String, Object> out = new LinkedHashMap<>();
        Map<String, Config> configs = new LinkedHashMap<>();
        for (String m : markets()) configs.put(m, config(m));
        out.put("markets", configs);
        out.put("max_lot", riskHolder.get().orders().maxLot());
        out.put("entry_max_age_minutes", ENTRY_MAX_AGE.toMinutes());
        JdbcClient db = jdbc.getObject();
        out.put("counts", db.sql("""
                        SELECT market, count(*) FILTER (WHERE status = 'SENT') AS sent,
                               count(*) FILTER (WHERE status = 'SKIPPED') AS skipped,
                               count(*) FILTER (WHERE status = 'REFUSED') AS refused
                          FROM roulette_entry WHERE created_at >= :from GROUP BY market
                        """).param("from", Timestamp.from(from)).query().listOfRows());
        // resultado real: o que o EA reportou ao fechar as posições abertas pela roleta
        out.put("closed", db.sql("""
                        WITH d AS (SELECT position_ticket, sum(profit + swap + commission) AS result
                                     FROM ea_deal GROUP BY position_ticket)
                        SELECT e.market, count(*) AS trades, sum(d.result) AS result_usd,
                               count(*) FILTER (WHERE d.result > 0) AS wins,
                               avg(d.result / nullif(o.risk_usd, 0)) AS avg_r,
                               sum(d.result) FILTER (WHERE d.result > 0) AS gains,
                               -sum(d.result) FILTER (WHERE d.result <= 0) AS losses
                          FROM roulette_entry e JOIN order_request o ON o.id = e.order_id
                          JOIN d ON d.position_ticket = o.ticket
                         WHERE e.created_at >= :from GROUP BY e.market
                        """).param("from", Timestamp.from(from)).query().listOfRows());
        out.put("open", db.sql("""
                        SELECT e.market, count(*) AS positions, sum(o.risk_usd) AS risk_usd, sum(p.profit) AS profit_usd
                          FROM roulette_entry e JOIN order_request o ON o.id = e.order_id
                          JOIN ea_position p ON p.ticket = o.ticket
                         GROUP BY e.market
                        """).query().listOfRows());
        // a mesma regra aplicada às previsões já resolvidas (stop/alvo/tempo com spread, como no placar)
        out.put("simulated", db.sql("""
                        SELECT market, model, horizon_min, count(*) AS n, avg(r) AS avg_r, sum(r) AS sum_r,
                               avg((r > 0)::int) AS hit_rate, min(moment_utc) AS since
                          FROM (SELECT market, model, horizon_min, moment_utc,
                                       CASE WHEN p_up > p_down THEN r_buy ELSE r_sell END AS r
                                  FROM prediction
                                 WHERE r_buy IS NOT NULL AND r_sell IS NOT NULL AND moment_utc >= :from
                                   AND p_flat < greatest(p_up, p_down)) x
                         GROUP BY market, model, horizon_min ORDER BY market, model, horizon_min
                        """).param("from", Timestamp.from(from)).query().listOfRows());
        out.put("entries", db.sql("""
                        SELECT e.id, e.market, e.symbol, e.model, e.horizon_min, e.side, e.p_side, e.status, e.message,
                               e.created_at, p.moment_utc, o.id AS order_id, o.status AS order_status,
                               o.message AS order_message, o.fill_price, o.ticket, o.risk_usd, o.close_after_utc
                          FROM roulette_entry e JOIN prediction p ON p.id = e.prediction_id
                          LEFT JOIN order_request o ON o.id = e.order_id
                         ORDER BY e.id DESC LIMIT 80
                        """).query().listOfRows());
        return out;
    }

    // ------------------------------------------------------------------ persistência

    private void save(String market, Config c) {
        try {
            repo.setSetting(KEY + market, mapper.writeValueAsString(c));
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private Config parse(String json) {
        try {
            return mapper.readValue(json, Config.class);
        } catch (Exception e) {
            return new Config(false, 60, "B", null, null, null, "estado ilegível: " + e.getMessage());
        }
    }
}
