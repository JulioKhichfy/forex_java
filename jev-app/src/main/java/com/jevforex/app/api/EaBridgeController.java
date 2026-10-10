package com.jevforex.app.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.jevforex.app.config.TradingProperties;
import com.jevforex.app.persistence.HeartbeatRepository;
import com.jevforex.app.trade.OrderService;
import com.jevforex.app.trade.TradeRepository;
import com.jevforex.collect.mt5.Mt5Properties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Ponte com o EA JevExecutor (documento mestre, capítulo 13). Quem inicia a conversa é sempre o EA.
 *
 * <p>Passo 6c: entrega as ordens que o operador aprovou no dashboard (já conferidas pelo {@link OrderService}),
 * recebe o resultado de cada execução e a foto das posições abertas no heartbeat.</p>
 */
@RestController
@Profile("!cli")
@RequestMapping("/api/ea")
public class EaBridgeController {

    private static final Logger log = LoggerFactory.getLogger(EaBridgeController.class);
    private static final Duration WARN_EVERY = Duration.ofMinutes(10);

    private final TradingProperties trading;
    private final Mt5Properties mt5;
    private final HeartbeatRepository heartbeats;
    private final TradeRepository trades;
    private final OrderService orders;
    private volatile Instant lastRealAccountWarning = Instant.EPOCH;

    public EaBridgeController(TradingProperties trading, Mt5Properties mt5, HeartbeatRepository heartbeats,
                              TradeRepository trades, OrderService orders) {
        this.trading = trading;
        this.mt5 = mt5;
        this.heartbeats = heartbeats;
        this.trades = trades;
        this.orders = orders;
    }

    /**
     * Texto simples, uma linha por item (parsing trivial em MQL5):
     * <pre>
     * MODE;SHADOW|DEMO|LIVE
     * CMD;NONE | CMD;BLOCK | CMD;FLATTEN
     * OPEN;id;símbolo;lado;volume;dist_sl;dist_tp;desvio_pontos;expira_em_utc;fechar_após_utc|-
     * CLOSE;id;ticket;desvio_pontos;expira_em_utc
     * </pre>
     * dist_sl/dist_tp são distâncias em PREÇO: o EA aplica sobre o preço real da execução. Cada ordem sai uma vez.
     */
    @GetMapping(value = "/signals", produces = MediaType.TEXT_PLAIN_VALUE)
    public String signals(@RequestParam(defaultValue = "0") long account,
                          @RequestParam(defaultValue = "") String server) {
        StringBuilder out = new StringBuilder("MODE;").append(trading.mode()).append('\n');
        String rejected = mt5.rejectAccount(account, server);
        if (rejected != null) {
            // falhar fechado: conta desconhecida nunca recebe ordem
            log.warn("EA pediu sinais com {} → BLOCK", rejected);
            return out.append("CMD;BLOCK\n").toString();
        }
        out.append("CMD;").append(orders.flattening() ? "FLATTEN" : orders.blocked() ? "BLOCK" : "NONE").append('\n');
        if (trading.mode() == TradingProperties.Mode.SHADOW) return out.toString();
        for (TradeRepository.Order o : trades.takePending(account)) {
            String expires = o.expiresAt().truncatedTo(ChronoUnit.SECONDS).toString();
            if ("OPEN".equals(o.action())) {
                out.append(String.format(Locale.ROOT, "OPEN;%d;%s;%s;%.2f;%.6f;%.6f;%d;%s;%s", o.id(), o.symbol(),
                        o.side(), o.volume(), o.slDistance(), o.tpDistance(), o.deviationPts(), expires,
                        o.closeAfterUtc() == null ? "-" : o.closeAfterUtc().truncatedTo(ChronoUnit.SECONDS)));
            } else {
                out.append(String.format(Locale.ROOT, "CLOSE;%d;%d;%d;%s", o.id(), o.ticket(), o.deviationPts(),
                        expires));
            }
            out.append('\n');
            log.info("Ordem {} entregue ao EA: {} {} {}", o.id(), o.action(), o.symbol(),
                    o.side() == null ? "#" + o.ticket() : o.side() + " " + o.volume());
        }
        return out.toString();
    }

    /**
     * Resultado de uma ordem: {"id": 12, "status": "FILLED"|"REJECTED", "ticket": 123, "price": 1.0834,
     * "message": "…"}.
     */
    @PostMapping(value = "/execution", produces = MediaType.TEXT_PLAIN_VALUE)
    public String execution(@RequestBody JsonNode body) {
        long id = body.path("id").asLong(0);
        String status = text(body, "status");
        if (id <= 0 || !("FILLED".equals(status) || "REJECTED".equals(status))) {
            return "RECUSADO;formato";
        }
        trades.finish(id, status, body.hasNonNull("ticket") ? body.path("ticket").asLong() : null, dbl(body, "price"),
                text(body, "message"));
        log.info("EA: ordem {} → {} {}", id, status, text(body, "message") == null ? "" : text(body, "message"));
        return "OK";
    }

    @PostMapping(value = "/heartbeat", produces = MediaType.TEXT_PLAIN_VALUE)
    public String heartbeat(@RequestBody JsonNode body) {
        long account = body.path("account").asLong(0);
        String server = text(body, "server");
        String tradeMode = text(body, "trade_mode");
        heartbeats.insert(new HeartbeatRepository.Heartbeat("ea", "fx", account, server, tradeMode,
                text(body, "ea_mode"), dbl(body, "equity"), dbl(body, "balance"), text(body, "currency"),
                body.has("positions") ? body.path("positions").asInt() : null,
                body.has("algo_enabled") ? body.path("algo_enabled").asBoolean() : null,
                body.has("connected") ? body.path("connected").asBoolean() : null,
                instant(body, "time"), body.toString()));

        if ("REAL".equalsIgnoreCase(tradeMode) && trading.mode() != TradingProperties.Mode.LIVE
                && Instant.now().isAfter(lastRealAccountWarning.plus(WARN_EVERY))) {
            lastRealAccountWarning = Instant.now();
            log.warn("ATENÇÃO: o EA está numa conta REAL ({} em {}) e trading.mode={}: nenhuma ordem será aceita "
                    + "até trading.mode=LIVE.", account, server, trading.mode());
        }
        String rejected = mt5.rejectAccount(account, server);
        if (rejected != null) {
            log.warn("Heartbeat recusado: {}", rejected);
            return "RECUSADO;" + rejected;
        }
        if (body.has("open")) {
            // foto das posições deste sistema (magic do EA) — base das travas de carteira
            List<TradeRepository.Position> positions = new ArrayList<>();
            Instant at = instant(body, "time") == null ? Instant.now() : instant(body, "time");
            for (JsonNode p : body.path("open")) {
                positions.add(new TradeRepository.Position(p.path("ticket").asLong(), "fx", account,
                        p.path("symbol").asText(), p.path("side").asText(), p.path("volume").asDouble(),
                        p.path("price").asDouble(), dbl(p, "sl"), dbl(p, "tp"), dbl(p, "profit"),
                        p.hasNonNull("magic") ? p.path("magic").asLong() : null, instant(p, "time"), at));
            }
            trades.replacePositions(account, positions);
            if (positions.isEmpty() && orders.flattening()) {
                trades.setSetting(OrderService.SETTING_FLATTEN, "false");   // tudo fechado; o BLOCK continua ligado
                log.info("FLATTEN concluído: nenhuma posição aberta. BLOCK continua ligado até você desligar.");
            }
        }
        return "OK";
    }

    private static String text(JsonNode n, String f) {
        return n.hasNonNull(f) ? n.path(f).asText() : null;
    }

    private static Double dbl(JsonNode n, String f) {
        return n.hasNonNull(f) ? n.path(f).asDouble() : null;
    }

    private static Instant instant(JsonNode n, String f) {
        try {
            return n.hasNonNull(f) ? Instant.parse(n.path(f).asText()) : null;
        } catch (DateTimeParseException e) {
            return null;
        }
    }
}
