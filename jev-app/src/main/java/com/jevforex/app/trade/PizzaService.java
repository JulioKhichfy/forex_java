package com.jevforex.app.trade;

import com.jevforex.app.live.PredictionRepository;
import com.jevforex.core.Instrument;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.sql.Types;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Compra Pizza (pedido do operador em 28/10/2026, "vamos descontrair"): o operador define o valor da pizza (lucro da
 * partida, em US$) e o stop (perda da partida); ao JOGAR, o sistema abre as operações em que mais confia, até
 * {@code pizza.max-positions} ao mesmo tempo, e para quando o resultado da partida (fechadas + abertas) chega num dos dois
 * — aí fecha tudo.
 *
 * <p>Em que o sistema confia, nesta ordem: o SINAL da regra do mercado (live.signal-rules; ouro e índices validados
 * fora da amostra; demais mercados pelo gate 4) e, sem ele, uma previsão FORTE do modelo B (P do lado e diferença para o
 * lado oposto acima dos mínimos, LATERAL não). Nenhuma das duas é garantia: a melhor regra deu ~+0,1 R por operação.</p>
 *
 * <p>Travas como no Cassino (decisão do operador): {@link OrderService#CASINO_WAIVED}; valem as técnicas, o BLOCK e a
 * perda do dia/semana. Stop e alvo do mercado, saída por tempo no horizonte da previsão, lote mínimo. Ordem sem stop
 * calculado não sai (falhar fechado). Uma previsão é usada uma vez por partida.</p>
 */
@Service
@Profile("!cli")
public class PizzaService {

    private static final Logger log = LoggerFactory.getLogger(PizzaService.class);

    private final TradeRepository repo;
    private final OrderService orders;
    private final PredictionRepository predictions;
    private final PizzaProperties props;
    private final ObjectProvider<JdbcClient> jdbc;
    /** Ordem executada, sem posição aberta e sem o fechamento reportado por este tempo: algo deu errado. */
    private static final Duration MISSING_MAX = Duration.ofMinutes(3);
    private final Map<Long, Instant> missingSince = new HashMap<>();

    public PizzaService(TradeRepository repo, OrderService orders, PredictionRepository predictions, PizzaProperties props,
                        ObjectProvider<JdbcClient> jdbc) {
        this.repo = repo;
        this.orders = orders;
        this.predictions = predictions;
        this.props = props;
        this.jdbc = jdbc;
    }

    // ------------------------------------------------------------------ em que confiar (puro, testável)

    /**
     * Uma operação candidata.
     *
     * @param tier SIGNAL (a regra do mercado deu sinal neste lado) | STRONG (previsão forte, sem sinal)
     */
    public record Candidate(String market, String symbol, String side, long predictionId, int horizon, String tier,
                            double pSide, double margin, Instant moment, String reason) {

        double score() {
            return ("SIGNAL".equals(tier) ? 1 : 0) + margin;
        }
    }

    /** As candidatas de agora, a melhor por símbolo, da mais confiável para a menos. */
    static List<Candidate> rank(List<PredictionRepository.Prediction> preds, Instant now, PizzaProperties p) {
        Map<String, Candidate> best = new HashMap<>();
        for (PredictionRepository.Prediction x : preds) {
            if (!"B".equals(x.model()) || x.atr() == null) continue;
            int maxAge = x.horizonMin() == 15 ? p.maxAge15Minutes() : x.horizonMin() == 60 ? p.maxAge60Minutes() : -1;
            long age = Duration.between(x.momentUtc(), now).toMinutes();
            if (maxAge < 0 || age > maxAge || age < 0) continue;
            if (x.pFlat() >= Math.max(x.pUp(), x.pDown())) continue;   // LATERAL mais provável: não
            String side = x.pUp() >= x.pDown() ? "BUY" : "SELL";
            double pSide = Math.max(x.pUp(), x.pDown());
            double margin = Math.abs(x.pUp() - x.pDown());
            String tier;
            if (side.equals(x.signal())) tier = "SIGNAL";
            else if (pSide >= p.strongMinProb() && margin >= p.strongMinMargin()) tier = "STRONG";
            else continue;
            String reason = String.format(Locale.ROOT, "%s · B %d min (%s): %s %.0f%% × %.0f%%",
                    "SIGNAL".equals(tier) ? "sinal da regra do mercado" : "previsão forte", x.horizonMin(),
                    "EVENT".equals(x.kind()) ? "evento" : "hora", "BUY".equals(side) ? "alta" : "queda", pSide * 100,
                    Math.min(x.pUp(), x.pDown()) * 100);
            Candidate c = new Candidate(x.market(), x.symbol(), side, x.id(), x.horizonMin(), tier, pSide, margin,
                    x.momentUtc(), reason);
            Candidate old = best.get(x.symbol());
            if (old == null || c.score() > old.score()) best.put(x.symbol(), c);
        }
        return best.values().stream().sorted(Comparator.comparingDouble(Candidate::score).reversed()).toList();
    }

    /** Puro: a partida acabou? "PIZZA" | "NO_PIZZA" | null. */
    static String outcome(double result, double pizzaUsd, double stopUsd) {
        if (result >= pizzaUsd - 1e-9) return "PIZZA";
        if (result <= -stopUsd + 1e-9) return "NO_PIZZA";
        return null;
    }

    // ------------------------------------------------------------------ comandos

    public synchronized Map<String, Object> start(double pizzaUsd, double stopUsd) {
        if (!(pizzaUsd > 0) || !(stopUsd > 0)) throw new IllegalArgumentException("o valor da pizza e o stop precisam ser > 0");
        if (current() != null) throw new IllegalArgumentException("já há uma partida em andamento");
        if (orders.blocked()) throw new IllegalArgumentException("BLOCK ligado: nenhuma ordem sai");
        long id = db().sql("""
                        INSERT INTO pizza_game (pizza_usd, stop_usd, max_positions, status, message)
                        VALUES (:p, :s, :m, 'RUNNING', 'procurando operações') RETURNING id
                        """).param("p", pizzaUsd).param("s", stopUsd).param("m", props.maxPositions())
                .query(Long.class).single();
        log.info("Compra Pizza: partida {} — pizza US$ {} · stop US$ {}", id, pizzaUsd, stopUsd);
        step(id);
        return Map.of("id", id);
    }

    public synchronized void stop() {
        Map<String, Object> g = current();
        if (g == null) return;
        db().sql("UPDATE pizza_game SET status = 'CLOSING_STOPPED', message = 'desistiu: fechando as posições' WHERE id = :id")
                .param("id", ((Number) g.get("id")).longValue()).update();
    }

    // ------------------------------------------------------------------ o laço

    @Scheduled(initialDelayString = "PT40S", fixedDelayString = "PT5S")
    public synchronized void tick() {
        Map<String, Object> g = current();
        if (g == null) return;
        try {
            step(((Number) g.get("id")).longValue());
        } catch (RuntimeException e) {
            log.warn("Compra Pizza: {}", e.toString());
        }
    }

    private void step(long id) {
        Map<String, Object> g = game(id);
        String status = (String) g.get("status");
        settle(id);
        double result = result(id);
        if ("RUNNING".equals(status)) {
            String out = outcome(result, num(g.get("pizza_usd")), num(g.get("stop_usd")));
            if (out == null) {
                open(id, ((Number) g.get("max_positions")).intValue());
                return;
            }
            status = "CLOSING_" + out;
            String msg = String.format(Locale.ROOT, "PIZZA".equals(out) ? "pizza garantida (US$ %+.2f): fechando as posições"
                    : "stop da partida (US$ %+.2f): fechando as posições", result);
            db().sql("UPDATE pizza_game SET status = :st, message = :m WHERE id = :id").param("st", status).param("m", msg)
                    .param("id", id).update();
            log.info("Compra Pizza: partida {} — {}", id, msg);
        }
        if (status.startsWith("CLOSING_")) {
            closeOpen(id);
            if (sentCount(id) > 0) return;   // ainda há ordem pendente ou posição aberta
            String fin = status.substring("CLOSING_".length());
            double r = result(id);
            String msg = switch (fin) {
                case "PIZZA" -> String.format(Locale.ROOT, "🍕 Pizza comprada! Resultado US$ %+.2f", r);
                case "NO_PIZZA" -> String.format(Locale.ROOT, "Sem pizza hoje: resultado US$ %+.2f", r);
                default -> String.format(Locale.ROOT, "Partida encerrada: resultado US$ %+.2f", r);
            };
            db().sql("UPDATE pizza_game SET status = :st, message = :m, result_usd = :r, ended_at = now() WHERE id = :id")
                    .param("st", fin).param("m", msg).param("r", r).param("id", id).update();
            log.info("Compra Pizza: partida {} — {}", id, msg);
        }
    }

    /** Abre as melhores candidatas até completar as vagas. */
    private void open(long id, int maxPositions) {
        int slots = maxPositions - (int) sentCount(id);
        if (slots <= 0) return;
        Set<String> busy = new HashSet<>(db().sql("SELECT symbol FROM pizza_entry WHERE game_id = :g AND status = 'SENT'")
                .param("g", id).query(String.class).list());
        Set<Long> used = new HashSet<>(db().sql("SELECT prediction_id FROM pizza_entry WHERE game_id = :g AND prediction_id IS NOT NULL")
                .param("g", id).query(Long.class).list());
        for (Candidate c : candidates()) {
            if (slots <= 0) break;
            if (busy.contains(c.symbol()) || used.contains(c.predictionId())) continue;
            Instrument inst = orders.instrument(c.symbol());
            Double lot = inst == null ? null : inst.minLot();
            OrderService.Roulette r = OrderService.Roulette.pizza(c.horizon(), c.predictionId());
            OrderService.Preview pv = orders.preview(c.symbol(), c.side(), lot, r);
            if (!pv.ok() || pv.slDistance() == null) {
                String failed = pv.slDistance() == null ? "sem stop calculado"
                        : pv.checks().stream().filter(x -> !x.ok()).map(x -> x.gate() + " (" + x.value() + ")")
                        .collect(Collectors.joining("; "));
                insert(id, c, null, "REFUSED", "recusada: " + failed);
                continue;
            }
            OrderService.Submit s = orders.submit(c.symbol(), c.side(), lot, r);
            if (s.orderId() == null) {
                insert(id, c, null, "REFUSED", "recusada no envio");
                continue;
            }
            insert(id, c, s.orderId(), "SENT", String.format(Locale.ROOT, "%s %.2f · stop US$ %.2f · alvo US$ %.2f · %d min",
                    c.side(), s.preview().lot(), nz(s.preview().riskUsd()), nz(s.preview().rewardUsd()), c.horizon()));
            busy.add(c.symbol());
            slots--;
            log.info("Compra Pizza: partida {} {} {} (ordem {}) — {}", id, c.side(), c.symbol(), s.orderId(), c.reason());
        }
    }

    /** As candidatas de agora em todos os mercados do jogo. */
    public List<Candidate> candidates() {
        List<PredictionRepository.Prediction> all = new ArrayList<>();
        for (String m : props.markets()) all.addAll(predictions.latest(m));
        return rank(all, Instant.now(), props);
    }

    // ------------------------------------------------------------------ resultado

    /** Fecha as contas das entradas: ordem que não executou, posição fechada (resultado do EA). */
    private void settle(long id) {
        Set<Long> open = repo.ownPositions().stream().map(TradeRepository.Position::ticket).collect(Collectors.toSet());
        for (Map<String, Object> r : db().sql("""
                        SELECT e.id, o.status, o.ticket, o.message,
                               (SELECT sum(d.profit + d.swap + d.commission) FROM ea_deal d WHERE d.position_ticket = o.ticket) AS result
                          FROM pizza_entry e JOIN order_request o ON o.id = e.order_id
                         WHERE e.game_id = :g AND e.status = 'SENT'
                        """).param("g", id).query().listOfRows()) {
            long eid = ((Number) r.get("id")).longValue();
            String st = (String) r.get("status");
            Long ticket = r.get("ticket") == null ? null : ((Number) r.get("ticket")).longValue();
            if ("PENDING".equals(st) || "SENT".equals(st)) continue;
            if (!"FILLED".equals(st)) {
                finishEntry(eid, "FAILED", null, "ordem " + st + (r.get("message") == null ? "" : ": " + r.get("message")));
            } else if (ticket != null && open.contains(ticket)) {
                missingSince.remove(eid);
            } else if (r.get("result") != null) {
                double v = ((Number) r.get("result")).doubleValue();
                finishEntry(eid, "DONE", v, String.format(Locale.ROOT, "fechou: US$ %+.2f", v));
                missingSince.remove(eid);
            } else if (missingSince.computeIfAbsent(eid, k -> Instant.now()).plus(MISSING_MAX).isBefore(Instant.now())) {
                finishEntry(eid, "FAILED", null, "a posição sumiu sem o fechamento reportado pelo EA");
                missingSince.remove(eid);
            }
        }
    }

    /** Resultado da partida: fechadas + abertas (lucro flutuante do MT5), em US$. */
    double result(long id) {
        Map<Long, Double> floating = new HashMap<>();
        for (TradeRepository.Position p : repo.ownPositions()) floating.put(p.ticket(), p.profit() == null ? 0 : p.profit());
        double sum = 0;
        for (Map<String, Object> r : db().sql("""
                        SELECT e.status, e.result_usd, o.ticket FROM pizza_entry e JOIN order_request o ON o.id = e.order_id
                         WHERE e.game_id = :g AND e.status IN ('SENT', 'DONE')
                        """).param("g", id).query().listOfRows()) {
            if (r.get("result_usd") != null) sum += ((Number) r.get("result_usd")).doubleValue();
            else if (r.get("ticket") != null) sum += floating.getOrDefault(((Number) r.get("ticket")).longValue(), 0.0);
        }
        return sum;
    }

    private void closeOpen(long id) {
        Set<Long> open = repo.ownPositions().stream().map(TradeRepository.Position::ticket).collect(Collectors.toSet());
        for (Long ticket : db().sql("""
                        SELECT o.ticket FROM pizza_entry e JOIN order_request o ON o.id = e.order_id
                         WHERE e.game_id = :g AND e.status = 'SENT' AND o.ticket IS NOT NULL
                           AND NOT EXISTS (SELECT 1 FROM order_request c WHERE c.action = 'CLOSE' AND c.ticket = o.ticket
                                            AND (c.status = 'FILLED' OR (c.status IN ('PENDING', 'SENT') AND c.expires_at > now())))
                        """).param("g", id).query(Long.class).list()) {
            if (!open.contains(ticket)) continue;
            try {
                orders.close(ticket, "Compra Pizza: fim da partida");
            } catch (RuntimeException e) {
                log.warn("Compra Pizza: não consegui fechar a posição {}: {}", ticket, e.getMessage());
            }
        }
    }

    // ------------------------------------------------------------------ a página

    public Map<String, Object> view() {
        Map<String, Object> out = new LinkedHashMap<>();
        Map<String, Object> g = current();
        if (g == null) {
            g = db().sql("SELECT * FROM pizza_game ORDER BY id DESC LIMIT 1").query().listOfRows().stream().findFirst().orElse(null);
        }
        if (g != null) {
            long id = ((Number) g.get("id")).longValue();
            Map<String, Object> game = new LinkedHashMap<>(g);
            boolean live = String.valueOf(g.get("status")).equals("RUNNING") || String.valueOf(g.get("status")).startsWith("CLOSING_");
            game.put("live", live);
            game.put("result_now", live ? result(id) : g.get("result_usd"));
            out.put("game", game);
            Map<Long, Double> floating = new HashMap<>();
            for (TradeRepository.Position p : repo.ownPositions()) floating.put(p.ticket(), p.profit() == null ? 0 : p.profit());
            List<Map<String, Object>> entries = new ArrayList<>();
            for (Map<String, Object> r : db().sql("""
                            SELECT e.*, o.ticket, o.status AS order_status FROM pizza_entry e
                              LEFT JOIN order_request o ON o.id = e.order_id
                             WHERE e.game_id = :g ORDER BY e.id DESC LIMIT 100
                            """).param("g", id).query().listOfRows()) {
                Map<String, Object> m = new LinkedHashMap<>(r);
                if ("SENT".equals(r.get("status")) && r.get("ticket") != null) {
                    m.put("floating_usd", floating.get(((Number) r.get("ticket")).longValue()));
                }
                entries.add(m);
            }
            out.put("entries", entries);
        }
        out.put("candidates", candidates());
        out.put("history", db().sql("""
                        SELECT g.id, g.pizza_usd, g.stop_usd, g.status, g.message, g.result_usd, g.started_at, g.ended_at,
                               (SELECT count(*) FROM pizza_entry e WHERE e.game_id = g.id AND e.order_id IS NOT NULL) AS trades
                          FROM pizza_game g ORDER BY g.id DESC LIMIT 15
                        """).query().listOfRows());
        out.put("config", props);
        return out;
    }

    // ------------------------------------------------------------------ apoio

    private Map<String, Object> current() {
        return db().sql("SELECT * FROM pizza_game WHERE status = 'RUNNING' OR status LIKE 'CLOSING_%' ORDER BY id DESC LIMIT 1")
                .query().listOfRows().stream().findFirst().orElse(null);
    }

    private Map<String, Object> game(long id) {
        return db().sql("SELECT * FROM pizza_game WHERE id = :id").param("id", id).query().listOfRows().get(0);
    }

    private long sentCount(long id) {
        return db().sql("SELECT count(*) FROM pizza_entry WHERE game_id = :g AND status = 'SENT'").param("g", id)
                .query(Long.class).single();
    }

    private void insert(long id, Candidate c, Long orderId, String status, String message) {
        db().sql("""
                        INSERT INTO pizza_entry (game_id, market, symbol, side, prediction_id, tier, reason, order_id, status, message,
                                                 done_at)
                        VALUES (:g, :m, :s, :side, :p, :t, :r, :o, :st, :msg, CASE WHEN :st = 'SENT' THEN NULL ELSE now() END)
                        """).param("g", id).param("m", c.market()).param("s", c.symbol()).param("side", c.side())
                .param("p", c.predictionId()).param("t", c.tier()).param("r", c.reason()).param("o", orderId, Types.BIGINT)
                .param("st", status).param("msg", message, Types.VARCHAR).update();
    }

    private void finishEntry(long id, String status, Double result, String message) {
        db().sql("UPDATE pizza_entry SET status = :st, result_usd = :r, message = :m, done_at = now() WHERE id = :id")
                .param("st", status).param("r", result, Types.DOUBLE).param("m", message, Types.VARCHAR).param("id", id).update();
    }

    private static double num(Object o) {
        return o instanceof Number n ? n.doubleValue() : 0;
    }

    private static double nz(Double d) {
        return d == null ? 0 : d;
    }

    private JdbcClient db() {
        return jdbc.getObject();
    }
}
