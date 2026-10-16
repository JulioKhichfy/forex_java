package com.jevforex.app.trade;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jevforex.core.risk.RiskSettings;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Trava "Sessão até a saída por tempo" (16/10/2026: USDCAD aberto às 20:02 de sexta, saída em 60 min). */
class SessionCheckTest {

    private static final RiskSettings.Orders O = new RiskSettings.Orders(0.01, 0.05, 60, 20, 30, 30, 90, 10, 2, 2.0, 300, 5);
    private static final Instant SEXTA_2002 = Instant.parse("2026-10-16T20:02:00Z");

    private static ObjectNode quote(String sessionEnd) {
        ObjectNode q = new ObjectMapper().createObjectNode().put("symbol", "USDCAD").put("bid", 1.4).put("ask", 1.4001);
        if (sessionEnd != null) q.put("session_end", sessionEnd);
        return q;
    }

    @Test
    void sextaPerto_doFechamento_recusaSaidaDepoisDaSessao() {
        var c = OrderService.sessionCheck(quote("2026-10-16T21:00:00Z"), SEXTA_2002, 60, O);
        assertFalse(c.ok(), c.value());
        assertTrue(OrderService.sessionCheck(quote("2026-10-16T21:00:00Z"), SEXTA_2002, 15, O).ok(), "15 min cabem");
        assertFalse(OrderService.sessionCheck(quote("2026-10-16T21:00:00Z"), SEXTA_2002, 55, O).ok(), "margem de 5 min");
    }

    @Test
    void mercadoFechado_eSemSaidaPorTempo() {
        assertFalse(OrderService.sessionCheck(quote(""), SEXTA_2002, 15, O).ok(), "fora de sessão");
        assertTrue(OrderService.sessionCheck(quote(""), SEXTA_2002, 0, O).ok(), "só SL/TP: não depende da sessão");
    }

    @Test
    void eaAntigo_semOCampo_passaAvisando() {
        var c = OrderService.sessionCheck(quote(null), SEXTA_2002, 60, O);
        assertTrue(c.ok());
        assertTrue(c.value().contains("2.5"), c.value());
    }
}
