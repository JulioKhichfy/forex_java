package com.jevforex.app.trade;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jevforex.app.config.RiskSettingsHolder;
import com.jevforex.app.live.LivePredictionService;
import com.jevforex.app.live.LiveProperties;
import com.jevforex.app.live.PredictionRepository;
import com.jevforex.core.Instrument;
import com.jevforex.core.Market;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.sql.Types;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Sinal automático (pedido do operador em 21/10/2026): com um mercado ligado, o sinal da REGRA do mercado
 * ({@code live.signal-rules}, hoje só o ouro: B 15 min, só depois de divulgação) vira ordem sozinho, no lado do sinal,
 * com o lote escolhido. Diferente da roleta, TODAS as travas de risco valem (falhar fechado): o que for recusado fica
 * registrado com o motivo. Sinal mais velho que {@code live.auto-max-age-seconds} não vira ordem — no histórico do ouro,
 * entrar 5 min depois levava o resultado para perto de zero.
 */
@Service
@Profile("!cli")
public class AutoSignalService {

    private static final Logger log = LoggerFactory.getLogger(AutoSignalService.class);
    private static final String KEY = "autosignal.";
    /** Sinais até esta idade são vistos (e registrados como atrasados, se passarem do limite de entrada). */
    private static final Duration SEEN_MAX_AGE = Duration.ofMinutes(15);

    private final TradeRepository repo;
    private final OrderService orders;
    private final PredictionRepository predictions;
    private final LivePredictionService live;
    private final RiskSettingsHolder riskHolder;
    private final ObjectProvider<JdbcClient> jdbc;
    private final ObjectMapper mapper;
    private final List<String> allowed;
    private final Duration maxAge;

    public AutoSignalService(TradeRepository repo, OrderService orders, PredictionRepository predictions,
                             LivePredictionService live, RiskSettingsHolder riskHolder, ObjectProvider<JdbcClient> jdbc,
                             ObjectMapper mapper, @Value("${live.auto-markets:metals}") List<String> allowed,
                             @Value("${live.auto-max-age-seconds:180}") int maxAgeSeconds) {
        this.repo = repo;
        this.orders = orders;
        this.predictions = predictions;
        this.live = live;
        this.riskHolder = riskHolder;
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.allowed = List.copyOf(allowed);
        this.maxAge = Duration.ofSeconds(maxAgeSeconds);
    }

    /** @param lot null = o lote mínimo de cada símbolo */
    public record Config(boolean on, Double lot, Instant startedAt, Instant stoppedAt, String stopReason) {
    }

    public List<String> allowed() {
        return allowed;
    }

    public Config config(String market) {
        return repo.setting(KEY + market).map(this::parse).orElse(new Config(false, null, null, null, null));
    }

    public synchronized Config start(String market, Double lot) {
        String m = Market.fromCode(market).code();
        if (!allowed.contains(m)) throw new IllegalArgumentException("Sinal automático só em " + allowed + " (live.auto-markets)");
        if (live.rule(m) == null) throw new IllegalArgumentException("O mercado " + m + " não tem regra de sinal (live.signal-rules)");
        if (lot != null && (!(lot > 0) || lot > riskHolder.get().orders().maxLot() + 1e-9)) {
            throw new IllegalArgumentException(String.format(Locale.ROOT,
                    "Lote %.2f fora do permitido (até %.2f, trading.risk.orders.max-lot)", lot, riskHolder.get().orders().maxLot()));
        }
        Config c = new Config(true, lot, Instant.now(), null, null);
        save(m, c);
        log.info("Sinal automático {} LIGADO (lote {})", m, lot == null ? "mínimo" : lot);
        return c;
    }

    public synchronized Config stop(String market, String reason) {
        String m = Market.fromCode(market).code();
        Config c = config(m);
        if (!c.on()) return c;
        Config off = new Config(false, c.lot(), c.startedAt(), Instant.now(), reason);
        save(m, off);
        log.info("Sinal automático {} desligado: {}", m, reason);
        return off;
    }

    // ------------------------------------------------------------------ o laço (a cada 5 s: o sinal vale por poucos minutos)

    @Scheduled(initialDelayString = "PT35S", fixedDelayString = "PT5S")
    public synchronized void tick() {
        for (String market : allowed) {
            Config c = config(market);
            if (!c.on()) continue;
            try {
                run(market, c);
            } catch (RuntimeException e) {
                log.warn("Sinal automático {}: {}", market, e.toString());
            }
        }
    }

    private void run(String market, Config c) {
        LiveProperties.SignalRule rule = live.rule(market);
        if (rule == null) return;
        Instant now = Instant.now();
        for (PredictionRepository.Prediction p : predictions.latest(market)) {
            if (!isRuleSignal(p, rule)) continue;
            Duration age = Duration.between(p.momentUtc(), now);
            if (age.compareTo(SEEN_MAX_AGE) > 0) continue;
            if (c.startedAt() != null && p.createdAt() != null && p.createdAt().isBefore(c.startedAt())) continue;   // só sinais depois de ligar
            Long entry = claim(market, p);   // uma vez por sinal, mesmo se o servidor cair no meio
            if (entry == null) continue;
            double pSide = "BUY".equals(p.signal()) ? p.pUp() : p.pDown();
            if (age.compareTo(maxAge) > 0) {
                finish(entry, p.signal(), pSide, null, "LATE", String.format(Locale.ROOT,
                        "sinal de %d min: entrar atrasado leva a vantagem embora (limite %d s)", age.toMinutes(), maxAge.toSeconds()));
                continue;
            }
            Instrument inst = orders.instrument(p.symbol());
            Double lot = c.lot() != null ? c.lot() : inst == null ? null : inst.minLot();
            OrderService.Submit s = orders.submit(p.symbol(), p.signal(), lot);   // TODAS as travas
            if (s.orderId() != null) {
                finish(entry, p.signal(), pSide, s.orderId(), "SENT", String.format(Locale.ROOT,
                        "%s %.2f %s · perda no stop US$ %.2f · fecha em %s min", p.signal(), s.preview().lot(), p.symbol(),
                        s.preview().riskUsd() == null ? 0 : s.preview().riskUsd(), s.preview().closeAfterMinutes()));
                log.info("Sinal automático {}: {} {} {} (ordem {})", market, p.signal(), s.preview().lot(), p.symbol(), s.orderId());
            } else {
                List<String> failed = s.preview().checks().stream().filter(x -> !x.ok())
                        .map(x -> x.gate() + " (" + x.value() + ")").toList();
                finish(entry, p.signal(), pSide, null, "REFUSED", String.join("; ", failed));
                log.warn("Sinal automático {}: {} {} recusado pelas travas: {}", market, p.signal(), p.symbol(), failed);
            }
        }
    }

    /** O sinal da regra do mercado: modelo e horizonte da regra, e passou nela (BUY/SELL). */
    static boolean isRuleSignal(PredictionRepository.Prediction p, LiveProperties.SignalRule rule) {
        return !"NO_TRADE".equals(p.signal()) && p.model().equals(rule.model()) && p.horizonMin() == rule.horizon();
    }

    private Long claim(String market, PredictionRepository.Prediction p) {
        return jdbc.getObject().sql("""
                        INSERT INTO auto_signal_entry (market, prediction_id, symbol, status, message)
                        VALUES (:m, :p, :s, 'SENDING', 'conferindo as travas')
                        ON CONFLICT (prediction_id) DO NOTHING RETURNING id
                        """)
                .param("m", market).param("p", p.id()).param("s", p.symbol()).query(Long.class).optional().orElse(null);
    }

    private void finish(long id, String side, double pSide, Long orderId, String status, String message) {
        jdbc.getObject().sql("""
                        UPDATE auto_signal_entry SET side = :side, p_side = :p, order_id = :o, status = :st, message = :msg
                         WHERE id = :id
                        """)
                .param("side", side, Types.VARCHAR).param("p", pSide).param("o", orderId, Types.BIGINT)
                .param("st", status).param("msg", message, Types.VARCHAR).param("id", id).update();
    }

    // ------------------------------------------------------------------ o que a página mostra

    public Map<String, Object> state() {
        Map<String, Object> out = new LinkedHashMap<>();
        Map<String, Object> markets = new LinkedHashMap<>();
        for (String m : allowed) {
            Map<String, Object> x = new LinkedHashMap<>();
            x.put("config", config(m));
            x.put("rule", live.rule(m));
            markets.put(m, x);
        }
        out.put("markets", markets);
        out.put("max_age_seconds", maxAge.toSeconds());
        JdbcClient db = jdbc.getObject();
        out.put("entries", db.sql("""
                        SELECT e.id, e.market, e.symbol, e.side, e.p_side, e.status, e.message, e.created_at, e.order_id,
                               o.status AS order_status, d.result
                          FROM auto_signal_entry e
                          LEFT JOIN order_request o ON o.id = e.order_id
                          LEFT JOIN (SELECT position_ticket, sum(profit + swap + commission) AS result FROM ea_deal
                                      GROUP BY position_ticket) d ON d.position_ticket = o.ticket
                         ORDER BY e.id DESC LIMIT 30
                        """).query().listOfRows());
        return out;
    }

    private Config parse(String json) {
        try {
            return mapper.readValue(json, Config.class);
        } catch (Exception e) {
            return new Config(false, null, null, null, "estado ilegível: " + e.getMessage());
        }
    }

    private void save(String market, Config c) {
        try {
            repo.setSetting(KEY + market, mapper.writeValueAsString(c));
        } catch (Exception e) {
            throw new IllegalStateException("Não consegui gravar o estado do sinal automático", e);
        }
    }
}
