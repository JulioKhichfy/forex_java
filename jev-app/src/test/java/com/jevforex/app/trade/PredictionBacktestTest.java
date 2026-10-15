package com.jevforex.app.trade;

import com.jevforex.app.live.PredictionRepository;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PredictionBacktestTest {

    private static PredictionRepository.Prediction pred(double down, double flat, double up) {
        return new PredictionRepository.Prediction(1L, "fx", "EURUSD", Instant.now(), "CONTROL", "SCHEDULE", "B", 60, "v",
                down, flat, up, "NO_TRADE", 0.0005, 1.1, 8, 10, "{}", null, null, "ALTA", 1.5, -1.0);
    }

    private static PredictionBacktest.Query q(String side, double minProb, boolean skipFlat) {
        return new PredictionBacktest.Query("fx", null, "48h", 60, "B", 0.01, side, minProb, skipFlat);
    }

    @Test
    void ladoMaisProvavel_filtros_eLateral() {
        var alta = pred(0.30, 0.25, 0.45);
        assertEquals("BUY", PredictionBacktest.decide(alta, q("all", 0, true)).side());
        assertNull(PredictionBacktest.decide(alta, q("SELL", 0, true)), "só venda: fora");
        assertNull(PredictionBacktest.decide(alta, q("all", 0.50, true)), "P mínima 50%: fora");
        var lateral = pred(0.25, 0.50, 0.25 + 1e-9);
        assertTrue(PredictionBacktest.decide(lateral, q("all", 0, true)).flat(), "LATERAL mais provável: pulada");
        assertFalse(PredictionBacktest.decide(lateral, q("all", 0, false)).flat(), "sem pular: opera pela direção");
        assertNull(PredictionBacktest.decide(pred(0.3, 0.4, 0.3), q("all", 0, false)), "empate alta × queda: sem lado");
    }

    @Test
    void periodos() {
        Instant now = Instant.parse("2026-10-15T14:37:00Z");
        assertEquals(Instant.parse("2026-10-15T13:37:00Z"), PredictionBacktest.from("1h", now));
        assertEquals(Instant.parse("2026-10-15T00:00:00Z"), PredictionBacktest.from("today", now));
        assertEquals(Instant.parse("2026-10-13T14:37:00Z"), PredictionBacktest.from("48h", now));
        assertEquals(Instant.parse("2026-09-15T14:37:00Z"), PredictionBacktest.from("30d", now));
    }
}
