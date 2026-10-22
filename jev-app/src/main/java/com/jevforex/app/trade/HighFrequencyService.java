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
 * Alta frequência (pedido do operador em 22/10/2026): com um mercado ligado, cada previsão nova do modelo e horizonte
 * escolhidos vira uma ordem no lado mais provável de cada símbolo (pula LATERAL), com ALVO EM US$ (ex.: US$ 0,10 com o
 * lote da ordem), stop largo SÓ DE PROTEÇÃO (stop-atr × ATR; o EA não aceita ordem sem stop e uma queda do MT5/PC
 * deixaria a posição sem limite) e saída por tempo no horizonte. Ignora TODAS as travas de risco por ordem
 * ({@link OrderService#HF_WAIVED}); valem as técnicas e a perda do dia/semana, que para todos os mercados.
 *
 * <p>Alvo pequeno não cria vantagem: muda o formato do resultado (ganhos pequenos frequentes, perdas raras e maiores
 * na saída por tempo); a média continua sendo a vantagem de direção da previsão menos o spread.</p>
 */
@Service
@Profile("!cli")
public class HighFrequencyService {

    private static final Logger log = LoggerFactory.getLogger(HighFrequencyService.class);
    private static final String KEY = "hf.";
    private static final Duration ENTRY_MAX_AGE = Duration.ofMinutes(10);

    private final TradeRepository repo;
    private final OrderService orders;
    private final PredictionRepository predictions;
    private final RiskSettingsHolder riskHolder;
    private final ObjectProvider<JdbcClient> jdbc;
    private final ObjectMapper mapper;

    public HighFrequencyService(TradeRepository repo, OrderService orders, PredictionRepository predictions,
                                RiskSettingsHolder riskHolder, ObjectProvider<JdbcClient> jdbc, ObjectMapper mapper) {
        this.repo = repo;
        this.orders = orders;
        this.predictions = predictions;
        this.riskHolder = riskHolder;
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    /**
     * @param lot     null = o lote mínimo de cada símbolo
     * @param takeUsd alvo em US$ por operação
     * @param stopAtr stop de proteção em ATR
     */
    public record Config(boolean on, String model, int horizon, Double lot, double takeUsd, double stopAtr,
                         Instant startedAt, Instant stoppedAt, String stopReason) {
    }

    public static List<String> markets() {
        return java.util.Arrays.stream(Market.values()).map(Market::code).toList();
    }

    public Config config(String market) {
        return repo.setting(KEY + market).map(this::parse)
                .orElse(new Config(false, "B", 15, null, 0.10, 3.0, null, null, null));
    }

    public synchronized Config start(String market, String model, int horizon, Double lot, double takeUsd, double stopAtr) {
        String m = Market.fromCode(market).code();
        if (!Set.of("A", "E", "B").contains(model)) throw new IllegalArgumentException("Modelo deve ser A, E ou B");
        if (horizon != 15 && horizon != 60) throw new IllegalArgumentException("Horizonte deve ser 15 ou 60 min");
        if (!(takeUsd > 0)) throw new IllegalArgumentException("O alvo em US$ precisa ser > 0");
        if (!(stopAtr >= 1)) throw new IllegalArgumentException("O stop de proteção precisa ser de pelo menos 1 ATR");
        if (lot != null && (!(lot > 0) || lot > riskHolder.get().orders().maxLot() + 1e-9)) {
            throw new IllegalArgumentException(String.format(Locale.ROOT,
                    "Lote %.2f fora do permitido (até %.2f, trading.risk.orders.max-lot)", lot, riskHolder.get().orders().maxLot()));
        }
        Config c = new Config(true, model, horizon, lot, takeUsd, stopAtr, Instant.now(), null, null);
        save(m, c);
        log.info("Alta frequência {} LIGADA: modelo {}, {} min, lote {}, alvo US$ {}, stop de proteção {} ATR", m, model,
                horizon, lot == null ? "mínimo" : lot, takeUsd, stopAtr);
        return c;
    }

    public synchronized Config stop(String market, String reason) {
        String m = Market.fromCode(market).code();
        Config c = config(m);
        if (!c.on()) return c;
        Config off = new Config(false, c.model(), c.horizon(), c.lot(), c.takeUsd(), c.stopAtr(), c.startedAt(),
                Instant.now(), reason);
        save(m, off);
        log.info("Alta frequência {} parada: {}", m, reason);
        return off;
    }

    public synchronized void stopAll(String reason) {
        for (String m : markets()) stop(m, reason);
    }

    // ------------------------------------------------------------------ o laço

    @Scheduled(initialDelayString = "PT45S", fixedDelayString = "PT10S")
    public synchronized void tick() {
        for (String market : markets()) {
            Config c = config(market);
            if (!c.on()) continue;
            try {
                if (!run(market, c)) return;   // disjuntor de perda: parou todos
            } catch (RuntimeException e) {
                log.warn("Alta frequência {}: {}", market, e.toString());
            }
        }
    }

    /** @return false = o disjuntor de perda do dia/semana parou todos os mercados */
    private boolean run(String market, Config c) {
        Instant now = Instant.now();
        for (PredictionRepository.Prediction p : predictions.latest(market)) {
            if (!p.model().equals(c.model()) || p.horizonMin() != c.horizon()) continue;
            if (p.momentUtc().isBefore(now.minus(ENTRY_MAX_AGE))) continue;
            if (c.startedAt() != null && p.createdAt() != null && p.createdAt().isBefore(c.startedAt())) continue;
            Long entry = claim(market, p);
            if (entry == null) continue;
            RouletteService.Decision d = RouletteService.decide(p);   // lado mais provável, pula LATERAL
            if (d.side() == null) {
                finish(entry, null, d.pSide(), null, "SKIPPED", d.why());
                continue;
            }
            Instrument inst = orders.instrument(p.symbol());
            Double lot = c.lot() != null ? c.lot() : inst == null ? null : inst.minLot();
            OrderService.Submit s = orders.submit(p.symbol(), d.side(), lot,
                    new OrderService.Roulette(c.horizon(), p.id(), c.stopAtr(), null, c.takeUsd(), true));
            if (s.orderId() != null) {
                finish(entry, d.side(), d.pSide(), s.orderId(), "SENT", String.format(Locale.ROOT,
                        "%s %.2f · alvo US$ %.2f · stop de proteção US$ %.2f · fecha em %d min", d.side(), s.preview().lot(),
                        c.takeUsd(), s.preview().riskUsd() == null ? 0 : s.preview().riskUsd(), c.horizon()));
                log.info("Alta frequência {}: {} {} {} (ordem {})", market, d.side(), s.preview().lot(), p.symbol(), s.orderId());
                continue;
            }
            List<String> failed = s.preview().checks().stream().filter(x -> !x.ok())
                    .map(x -> x.gate() + " (" + x.value() + ")").toList();
            finish(entry, d.side(), d.pSide(), null, "REFUSED", String.join("; ", failed));
            if (s.preview().checks().stream().anyMatch(x -> !x.ok() && x.gate().startsWith("Perda d"))) {
                stopAll("disjuntor de perda: " + String.join("; ", failed));
                return false;
            }
        }
        return true;
    }

    private Long claim(String market, PredictionRepository.Prediction p) {
        return jdbc.getObject().sql("""
                        INSERT INTO hf_entry (market, prediction_id, symbol, status, message)
                        VALUES (:m, :p, :s, 'SENDING', 'conferindo as travas')
                        ON CONFLICT (prediction_id) DO NOTHING RETURNING id
                        """)
                .param("m", market).param("p", p.id()).param("s", p.symbol()).query(Long.class).optional().orElse(null);
    }

    private void finish(long id, String side, double pSide, Long orderId, String status, String message) {
        jdbc.getObject().sql("""
                        UPDATE hf_entry SET side = :side, p_side = :p, order_id = :o, status = :st, message = :msg WHERE id = :id
                        """)
                .param("side", side, Types.VARCHAR).param("p", pSide).param("o", orderId, Types.BIGINT)
                .param("st", status).param("msg", message, Types.VARCHAR).param("id", id).update();
    }

    // ------------------------------------------------------------------ o que a página mostra

    public Map<String, Object> state(int days) {
        Instant from = Instant.now().minus(Duration.ofDays(Math.min(days, 365)));
        Map<String, Object> out = new LinkedHashMap<>();
        Map<String, Config> configs = new LinkedHashMap<>();
        for (String m : markets()) configs.put(m, config(m));
        out.put("markets", configs);
        JdbcClient db = jdbc.getObject();
        out.put("counts", db.sql("""
                        SELECT market, count(*) FILTER (WHERE status = 'SENT') AS sent,
                               count(*) FILTER (WHERE status = 'SKIPPED') AS skipped,
                               count(*) FILTER (WHERE status = 'REFUSED') AS refused
                          FROM hf_entry WHERE created_at >= :from GROUP BY market
                        """).param("from", Timestamp.from(from)).query().listOfRows());
        // resultado real (o que o EA reportou ao fechar), por mercado: quantas no alvo, quantas por tempo/stop
        out.put("closed", db.sql("""
                        WITH d AS (SELECT position_ticket, sum(profit + swap + commission) AS result,
                                          (array_agg(reason ORDER BY closed_at DESC))[1] AS reason
                                     FROM ea_deal
                                    WHERE account = (SELECT account FROM heartbeat WHERE component = 'ea'
                                                      ORDER BY reported_at DESC NULLS LAST, id DESC LIMIT 1)
                                    GROUP BY position_ticket)
                        SELECT e.market, count(*) AS trades, sum(d.result) AS result_usd,
                               count(*) FILTER (WHERE d.result > 0) AS wins,
                               count(*) FILTER (WHERE d.reason = 'TP') AS at_target,
                               sum(d.result) FILTER (WHERE d.result > 0) AS gains,
                               -sum(d.result) FILTER (WHERE d.result <= 0) AS losses,
                               min(d.result) AS worst
                          FROM hf_entry e JOIN order_request o ON o.id = e.order_id
                          JOIN d ON d.position_ticket = o.ticket
                         WHERE e.created_at >= :from GROUP BY e.market
                        """).param("from", Timestamp.from(from)).query().listOfRows());
        out.put("entries", db.sql("""
                        SELECT e.id, e.market, e.symbol, e.side, e.p_side, e.status, e.message, e.created_at
                          FROM hf_entry e ORDER BY e.id DESC LIMIT 40
                        """).query().listOfRows());
        return out;
    }

    private Config parse(String json) {
        try {
            return mapper.readValue(json, Config.class);
        } catch (Exception e) {
            return new Config(false, "B", 15, null, 0.10, 3.0, null, null, "estado ilegível: " + e.getMessage());
        }
    }

    private void save(String market, Config c) {
        try {
            repo.setSetting(KEY + market, mapper.writeValueAsString(c));
        } catch (Exception e) {
            throw new IllegalStateException("Não consegui gravar o estado da alta frequência", e);
        }
    }
}
