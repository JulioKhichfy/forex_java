package com.jevforex.app.trade;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jevforex.app.config.RiskSettingsHolder;
import com.jevforex.app.live.ScannerService;
import com.jevforex.core.Instrument;
import com.jevforex.features.scan.Bar;
import com.jevforex.features.scan.MomentumRule;
import com.jevforex.features.scan.Scanner;
import com.jevforex.features.scan.Tf;
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
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Momentum BTC (passo 12b, pedido do operador em 25/10/2026): ligada, a cada hora cheia em que a hora que acabou de
 * fechar teve movimento no topo das anteriores ({@link MomentumRule}), envia uma ordem A FAVOR com as saídas em que a
 * regra foi validada ({@link OrderService.Fixed}: stop e alvo em ATR de 1 h, saída por tempo). TODAS as travas valem
 * (falhar fechado); o que for recusado fica registrado com o motivo. Sinal mais velho que
 * {@code momentum.max-entry-delay-seconds} não vira ordem.
 */
@Service
@Profile("!cli")
public class MomentumService {

    private static final Logger log = LoggerFactory.getLogger(MomentumService.class);
    private static final String KEY = "momentum";
    /** Sinais até esta idade são registrados (como atrasados, se passarem do limite de entrada). */
    private static final Duration SEEN_MAX_AGE = Duration.ofHours(2);

    private final TradeRepository repo;
    private final OrderService orders;
    private final ScannerService scanner;
    private final MomentumProperties props;
    private final RiskSettingsHolder riskHolder;
    private final ObjectProvider<JdbcClient> jdbc;
    private final ObjectMapper mapper;

    public MomentumService(TradeRepository repo, OrderService orders, ScannerService scanner, MomentumProperties props,
                           RiskSettingsHolder riskHolder, ObjectProvider<JdbcClient> jdbc, ObjectMapper mapper) {
        this.repo = repo;
        this.orders = orders;
        this.scanner = scanner;
        this.props = props;
        this.riskHolder = riskHolder;
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    /** @param lot null = o lote mínimo do símbolo */
    public record Config(boolean on, Double lot, Instant startedAt, Instant stoppedAt, String stopReason) {
    }

    public Config config() {
        return repo.setting(KEY).map(this::parse).orElse(new Config(false, null, null, null, null));
    }

    public synchronized Config start(Double lot) {
        if (lot != null && (!(lot > 0) || lot > riskHolder.get().orders().maxLot() + 1e-9)) {
            throw new IllegalArgumentException(String.format(Locale.ROOT,
                    "Lote %.2f fora do permitido (até %.2f, trading.risk.orders.max-lot)", lot, riskHolder.get().orders().maxLot()));
        }
        Config c = new Config(true, lot, Instant.now(), null, null);
        save(c);
        log.info("Momentum {} LIGADO (lote {})", props.symbols(), lot == null ? "mínimo" : lot);
        return c;
    }

    public synchronized Config stop(String reason) {
        Config c = config();
        if (!c.on()) return c;
        Config off = new Config(false, c.lot(), c.startedAt(), Instant.now(), reason);
        save(off);
        log.info("Momentum desligado: {}", reason);
        return off;
    }

    // ------------------------------------------------------------------ o laço

    @Scheduled(initialDelayString = "PT50S", fixedDelayString = "PT15S")
    public synchronized void tick() {
        Config c = config();
        if (!c.on()) return;
        for (String symbol : props.symbols()) {
            try {
                run(symbol, c);
            } catch (RuntimeException e) {
                log.warn("Momentum {}: {}", symbol, e.toString());
            }
        }
    }

    private void run(String symbol, Config c) {
        Scanner.Input in = scanner.input(props.market(), symbol);
        if (in == null) return;
        List<Bar> h1 = in.bars().getOrDefault(Tf.H1, List.of()).stream().filter(Bar::closed).toList();
        MomentumRule.Signal s = MomentumRule.evaluate(h1, props.lookbackHours(), props.topPct(), props.atrPeriod());
        if (s == null) return;
        Instant barEnd = s.barStart().plusHours(1).toInstant(ZoneOffset.UTC);
        Duration age = Duration.between(barEnd, Instant.now());
        if (age.compareTo(SEEN_MAX_AGE) > 0) return;
        if (c.startedAt() != null && barEnd.isBefore(c.startedAt())) return;   // só horas que fecharam depois de ligar
        Long entry = claim(symbol, s);   // uma vez por hora, mesmo se o servidor cair no meio
        if (entry == null) return;
        if (age.toSeconds() > props.maxEntryDelaySeconds()) {
            finish(entry, null, "LATE", String.format(Locale.ROOT, "hora fechada há %d min (limite %d s): não é a regra testada",
                    age.toMinutes(), props.maxEntryDelaySeconds()));
            return;
        }
        Instrument inst = orders.instrument(symbol);
        Double lot = c.lot() != null ? c.lot() : inst == null ? null : inst.minLot();
        double stop = props.stopAtr() * s.atr();
        OrderService.Submit r = orders.submit(symbol, s.side(), lot,
                new OrderService.Fixed(props.closeAfterMinutes(), stop, props.targetR() * stop));   // TODAS as travas
        String what = String.format(Locale.ROOT, "hora de %+.2f%% (limiar %.2f%%) · %s · stop %.0f (%.1f ATR) · alvo %.1f× · %d h",
                s.ret() * 100, s.threshold() * 100, s.side(), stop, props.stopAtr(), props.targetR(), props.closeAfterMinutes() / 60);
        if (r.orderId() != null) {
            finish(entry, r.orderId(), "SENT", what);
            log.info("Momentum {}: {} {} ({}; ordem {})", symbol, s.side(), r.preview().lot(), what, r.orderId());
            return;
        }
        String failed = r.preview().checks().stream().filter(x -> !x.ok())
                .map(x -> x.gate() + " (" + x.value() + ")").reduce((a, b) -> a + "; " + b).orElse("?");
        finish(entry, null, "REFUSED", what + " → recusada: " + failed);
        log.info("Momentum {}: sinal recusado pelas travas — {}", symbol, failed);
    }

    private Long claim(String symbol, MomentumRule.Signal s) {
        return jdbc.getObject().sql("""
                        INSERT INTO momentum_entry (market, symbol, bar_start, side, ret, threshold, atr, status, message)
                        VALUES (:m, :sym, :b, :side, :ret, :thr, :atr, 'SENDING', 'conferindo as travas')
                        ON CONFLICT (symbol, bar_start) DO NOTHING RETURNING id
                        """)
                .param("m", props.market()).param("sym", symbol)
                .param("b", Timestamp.from(s.barStart().toInstant(ZoneOffset.UTC)), Types.TIMESTAMP)
                .param("side", s.side()).param("ret", s.ret()).param("thr", s.threshold()).param("atr", s.atr())
                .query(Long.class).optional().orElse(null);
    }

    private void finish(long id, Long orderId, String status, String message) {
        jdbc.getObject().sql("UPDATE momentum_entry SET order_id = :o, status = :st, message = :msg WHERE id = :id")
                .param("o", orderId, Types.BIGINT).param("st", status).param("msg", message, Types.VARCHAR)
                .param("id", id).update();
    }

    // ------------------------------------------------------------------ o que a página mostra

    public Map<String, Object> state() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("config", config());
        out.put("rule", props);
        JdbcClient db = jdbc.getObject();
        out.put("entries", db.sql("""
                        SELECT e.id, e.symbol, e.bar_start, e.side, e.ret, e.threshold, e.status, e.message, e.created_at,
                               (SELECT sum(d.profit + d.swap + d.commission) FROM order_request o JOIN ea_deal d
                                    ON d.position_ticket = o.ticket WHERE o.id = e.order_id) AS result_usd
                          FROM momentum_entry e ORDER BY e.id DESC LIMIT 20
                        """).query().listOfRows());
        out.put("totals", db.sql("""
                        SELECT count(*) FILTER (WHERE e.status = 'SENT') AS sent,
                               count(*) FILTER (WHERE e.status = 'REFUSED') AS refused,
                               count(*) FILTER (WHERE e.status = 'LATE') AS late,
                               sum(d.result) AS result_usd, count(d.result) AS closed
                          FROM momentum_entry e
                          LEFT JOIN order_request o ON o.id = e.order_id
                          LEFT JOIN (SELECT position_ticket, sum(profit + swap + commission) AS result FROM ea_deal
                                      GROUP BY position_ticket) d ON d.position_ticket = o.ticket
                         WHERE e.created_at >= :from
                        """).param("from", Timestamp.from(Instant.now().minus(Duration.ofDays(30)))).query().singleRow());
        return out;
    }

    private Config parse(String json) {
        try {
            return mapper.readValue(json, Config.class);
        } catch (Exception e) {
            return new Config(false, null, null, null, "estado ilegível: " + e.getMessage());
        }
    }

    private void save(Config c) {
        try {
            repo.setSetting(KEY, mapper.writeValueAsString(c));
        } catch (Exception e) {
            throw new IllegalStateException("Não consegui gravar o estado do Momentum", e);
        }
    }
}
