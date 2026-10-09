package com.jevforex.app.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.jevforex.app.config.TradingProperties;
import com.jevforex.app.persistence.HeartbeatRepository;
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

/**
 * Ponte com o EA JevExecutor (documento mestre, capítulo 13). Quem inicia a conversa é sempre o EA.
 *
 * <p>Passo 2: o EA roda em SHADOW. Ainda não há decision engine, então nenhum sinal é entregue:
 * o endpoint de sinais devolve só o modo e o comando de controle. Envio de ordens chega no passo 6.</p>
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
    private volatile Instant lastRealAccountWarning = Instant.EPOCH;

    public EaBridgeController(TradingProperties trading, Mt5Properties mt5, HeartbeatRepository heartbeats) {
        this.trading = trading;
        this.mt5 = mt5;
        this.heartbeats = heartbeats;
    }

    /**
     * Texto simples, uma linha por item (parsing trivial em MQL5):
     * <pre>
     * MODE;SHADOW
     * CMD;NONE | CMD;BLOCK
     * id;símbolo;lado;volume;sl;tp;desvio_pontos;expira_em_utc;fechar_após_utc   (a partir do passo 6)
     * </pre>
     */
    @GetMapping(value = "/signals", produces = MediaType.TEXT_PLAIN_VALUE)
    public String signals(@RequestParam(defaultValue = "0") long account,
                          @RequestParam(defaultValue = "") String server) {
        StringBuilder out = new StringBuilder("MODE;").append(trading.mode()).append('\n');
        String rejected = mt5.rejectAccount(account, server);
        if (rejected != null) {
            // falhar fechado: conta desconhecida nunca recebe sinal
            log.warn("EA pediu sinais com {} → BLOCK", rejected);
            return out.append("CMD;BLOCK\n").toString();
        }
        return out.append("CMD;NONE\n").toString();
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
            log.warn("ATENÇÃO: o EA está numa conta REAL ({} em {}) e trading.mode={}. O EA deste passo não "
                    + "envia ordens, mas use a conta demo.", account, server, trading.mode());
        }
        String rejected = mt5.rejectAccount(account, server);
        if (rejected != null) {
            log.warn("Heartbeat recusado: {}", rejected);
            return "RECUSADO;" + rejected;
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
