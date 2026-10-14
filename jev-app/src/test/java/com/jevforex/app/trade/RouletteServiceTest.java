package com.jevforex.app.trade;

import com.jevforex.app.live.PredictionRepository;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RouletteServiceTest {

    private static PredictionRepository.Prediction pred(double down, double flat, double up) {
        return new PredictionRepository.Prediction(1L, "fx", "EURUSD", Instant.now(), "CONTROL", "SCHEDULE", "B", 60, "v",
                down, flat, up, "NO_TRADE", 0.0005, 1.1, 8, 10, "{}", null, null, null, null, null);
    }

    @Test
    void ladoMaisProvavel_ePulaQuandoLateralLidera() {
        assertEquals("BUY", RouletteService.decide(pred(0.30, 0.25, 0.45)).side());
        assertEquals(0.45, RouletteService.decide(pred(0.30, 0.25, 0.45)).pSide(), 1e-12);
        assertEquals("SELL", RouletteService.decide(pred(0.40, 0.35, 0.25)).side());
        RouletteService.Decision flat = RouletteService.decide(pred(0.30, 0.40, 0.30));
        assertNull(flat.side(), "LATERAL mais provável: pula");
        assertTrue(flat.why().startsWith("LATERAL"));
        assertNull(RouletteService.decide(pred(0.20, 0.40, 0.40)).side(), "empate com LATERAL também pula");
    }

    @Test
    void travasIgnoradas_saoAsDeRisco_eExistemNoOrderService() throws Exception {
        String src = Files.readString(Path.of("src/main/java/com/jevforex/app/trade/OrderService.java"));
        for (String gate : OrderService.ROULETTE_WAIVED) {
            assertTrue(src.contains("new Check(\"" + gate + "\""), "trava inexistente no OrderService: " + gate);
        }
        // o operador escolheu manter o disjuntor de perda; as técnicas nunca saem
        for (String kept : List.of("Perda do dia", "Perda da semana", "EA conectado", "Modo × conta", "BLOCK / FLATTEN",
                "Cotação", "ATR (última previsão)", "Lote")) {
            assertFalse(OrderService.ROULETTE_WAIVED.contains(kept), kept);
            assertTrue(src.contains("new Check(\"" + kept + "\""), "trava mantida não encontrada: " + kept);
        }
    }
}
