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
    void pullback_soEmLateral_contraABanda() {
        var lateral = pred(0.25, 0.50, 0.25);
        assertEquals("BUY", RouletteService.decidePullback(lateral, 0.05, 0.1).side(), "perto da banda inferior");
        assertEquals("BUY", RouletteService.decidePullback(lateral, -0.2, 0.1).side(), "abaixo da banda inferior");
        assertEquals("SELL", RouletteService.decidePullback(lateral, 0.95, 0.1).side(), "perto da superior");
        assertEquals("SELL", RouletteService.decidePullback(lateral, 1.3, 0.0).side(), "fora da superior");
        assertNull(RouletteService.decidePullback(lateral, 0.5, 0.1).side(), "no meio das bandas");
        assertNull(RouletteService.decidePullback(lateral, 0.05, 0.0).side(), "band 0: só fora das bandas");
        assertNull(RouletteService.decidePullback(lateral, null, 0.1).side(), "sem bandas: falhar fechado");
        var alta = pred(0.20, 0.30, 0.50);
        assertTrue(RouletteService.decidePullback(alta, 0.0, 0.1).why().startsWith("não é LATERAL"));
    }

    @Test
    void asDuasJuntas_cadaPrevisaoVaiParaSuaPerna() {
        var lateral = pred(0.25, 0.50, 0.25);
        var alta = pred(0.20, 0.30, 0.50);
        assertEquals("pullback", RouletteService.leg("both", lateral));
        assertEquals("trend", RouletteService.leg("both", alta));
        assertEquals("trend", RouletteService.leg("trend", lateral), "só tendência: a decisão pula a LATERAL");
        assertEquals("pullback", RouletteService.leg("pullback", alta), "só pullback: a decisão pula a não LATERAL");
        // juntas: a alta entra pela tendência; a lateral, pela banda
        assertEquals("BUY", RouletteService.decide(alta).side());
        assertEquals("SELL", RouletteService.decidePullback(lateral, 0.97, 0.1).side());
    }

    @Test
    void configAntiga_semEstrategia_viraTendencia() {
        var c = new RouletteService.Config(true, 15, "B", null, null, null, null, null, null);
        assertEquals("trend", c.strategy());
        assertEquals(0.1, c.band(), 1e-12);
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
