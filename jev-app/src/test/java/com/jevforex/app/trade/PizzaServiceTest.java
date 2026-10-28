package com.jevforex.app.trade;

import com.jevforex.app.live.PredictionRepository;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PizzaServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-28T15:05:00Z");
    private static final PizzaProperties P = new PizzaProperties(null, 5, 0.45, 0.15, 10, 30);
    private static long ids = 1;

    private static PredictionRepository.Prediction pred(String symbol, String model, int h, int ageMin, double down,
                                                        double flat, double up, String signal, Double atr) {
        return new PredictionRepository.Prediction(ids++, "fx", symbol, NOW.minusSeconds(ageMin * 60L), "CONTROL", "SCHEDULED",
                model, h, "v1", down, flat, up, signal, atr, 1.1, 10, 5, null, NOW, null, null, null, null);
    }

    @Test
    void sinalDaRegraVemAntesDaPrevisaoForte() {
        var out = PizzaService.rank(List.of(
                pred("EURUSD", "B", 60, 5, 0.10, 0.30, 0.60, "NO_TRADE", 0.001),   // forte: 0,60 e diferença 0,50
                pred("XAUUSD", "B", 15, 2, 0.30, 0.25, 0.45, "BUY", 2.0)),          // sinal da regra, diferença 0,15
                NOW, P);
        assertEquals(List.of("XAUUSD", "EURUSD"), out.stream().map(PizzaService.Candidate::symbol).toList());
        assertEquals("SIGNAL", out.get(0).tier());
        assertEquals("STRONG", out.get(1).tier());
        assertEquals("BUY", out.get(1).side());
    }

    @Test
    void ficamDeFora_lateralFracaVelhaOutroModeloSemAtr() {
        var out = PizzaService.rank(List.of(
                pred("GBPUSD", "B", 60, 5, 0.20, 0.50, 0.30, "NO_TRADE", 0.001),   // LATERAL mais provável
                pred("USDJPY", "B", 60, 5, 0.30, 0.30, 0.40, "NO_TRADE", 0.1),     // fraca (diferença 0,10)
                pred("AUDUSD", "B", 15, 12, 0.10, 0.30, 0.60, "BUY", 0.001),       // 15 min com 12 min de idade
                pred("NZDUSD", "E", 60, 5, 0.10, 0.30, 0.60, "BUY", 0.001),        // outro modelo
                pred("USDCAD", "B", 60, 5, 0.10, 0.30, 0.60, "BUY", null)),        // sem ATR: sem stop
                NOW, P);
        assertTrue(out.isEmpty(), out.toString());
    }

    @Test
    void umaPorSimbolo_aMelhor() {
        var out = PizzaService.rank(List.of(
                pred("EURUSD", "B", 15, 2, 0.60, 0.20, 0.20, "NO_TRADE", 0.001),   // forte na queda (0,40)
                pred("EURUSD", "B", 60, 5, 0.25, 0.30, 0.45, "BUY", 0.001)),       // sinal na alta
                NOW, P);
        assertEquals(1, out.size());
        assertEquals("BUY", out.get(0).side());
        assertEquals(60, out.get(0).horizon());
    }

    @Test
    void fimDaPartida() {
        assertEquals("PIZZA", PizzaService.outcome(0.51, 0.50, 0.40));
        assertEquals("NO_PIZZA", PizzaService.outcome(-0.40, 0.50, 0.40));
        assertNull(PizzaService.outcome(0.10, 0.50, 0.40));
    }
}
