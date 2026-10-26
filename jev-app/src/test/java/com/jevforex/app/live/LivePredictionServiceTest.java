package com.jevforex.app.live;

import com.jevforex.ml.ExperimentConfig;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

class LivePredictionServiceTest {

    @Test
    void vetor_naOrdemDoTreino_paresEEventoENulos() {
        Map<String, Object> row = new HashMap<>();
        row.put("symbol", "USDJPY");
        row.put("kind", "EVENT");
        row.put("atr_bps", 4.5);
        row.put("surprise_diff", null);          // nulo vira 0, como no Dataset do treino
        row.put("dow", 3);                       // inteiro do DuckDB
        List<String> features = List.of("atr_bps", "surprise_diff", "is_event", "dow", "pair_EURUSD", "pair_USDJPY");
        assertArrayEquals(new double[]{4.5, 0, 1, 3, 0, 1}, LivePredictionService.vector(features, row, "USDJPY"), 1e-12);
    }

    @Test
    void gate4_probabilidadeEMargem() {
        ExperimentConfig.Decision gate = new ExperimentConfig.Decision(0.60, 0.35);
        assertEquals("BUY", LivePredictionService.signal(new double[]{0.10, 0.25, 0.65}, gate));
        assertEquals("SELL", LivePredictionService.signal(new double[]{0.62, 0.20, 0.18}, gate));
        assertEquals("NO_TRADE", LivePredictionService.signal(new double[]{0.30, 0.10, 0.60}, gate));   // margem 0,30
        assertEquals("NO_TRADE", LivePredictionService.signal(new double[]{0.20, 0.25, 0.55}, gate));   // P < 0,60
    }

    @Test
    void regraDoMercado_soNoModeloEHorizonteDela_eSoEmEvento() {
        ExperimentConfig.Decision gate = new ExperimentConfig.Decision(0.60, 0.35);
        var ouro = new LiveProperties.SignalRule("B", 15, 0.45, 0.15, true, null, null);
        double[] p = {0.20, 0.30, 0.50};   // passa na regra do ouro, não no gate 4
        assertEquals("BUY", LivePredictionService.signal(p, gate, ouro, "B", 15, "EVENT"));
        assertEquals("NO_TRADE", LivePredictionService.signal(p, gate, ouro, "B", 15, "CONTROL"));   // só em evento
        assertEquals("NO_TRADE", LivePredictionService.signal(p, gate, ouro, "B", 60, "EVENT"));     // outro horizonte: gate 4
        assertEquals("NO_TRADE", LivePredictionService.signal(p, gate, ouro, "A", 15, "EVENT"));     // outro modelo: gate 4
        assertEquals("SELL", LivePredictionService.signal(new double[]{0.46, 0.24, 0.30}, gate, ouro, "B", 15, "EVENT"));
        assertEquals("NO_TRADE", LivePredictionService.signal(new double[]{0.46, 0.20, 0.34}, gate, ouro, "B", 15, "EVENT"));   // margem 0,12
        assertEquals("NO_TRADE", LivePredictionService.signal(p, gate, null, "B", 15, "EVENT"));     // sem regra: gate 4
        // regra larga (22/10/2026): todos os momentos, sem P mínima, margem >= 0,10, pula LATERAL
        var larga = new LiveProperties.SignalRule("B", 15, 0.0, 0.10, false, null, true);
        assertEquals("BUY", LivePredictionService.signal(new double[]{0.25, 0.35, 0.40}, gate, larga, "B", 15, "CONTROL"), "hora cheia vale");
        assertEquals("NO_TRADE", LivePredictionService.signal(new double[]{0.10, 0.60, 0.30}, gate, larga, "B", 15, "EVENT"), "LATERAL o mais provável");
        assertEquals("NO_TRADE", LivePredictionService.signal(new double[]{0.30, 0.32, 0.38}, gate, larga, "B", 15, "EVENT"), "margem 0,08");
    }

    @Test
    void agendador_confereOsMinutosPerdidos_comLimite() {
        var t = java.time.LocalDateTime.parse("2026-10-13T14:04");
        // rodada das 14:00 terminou às 14:04: confere 14:01…14:04 (o evento de 14:00 tem momento às 14:02)
        assertEquals(List.of(t.minusMinutes(3), t.minusMinutes(2), t.minusMinutes(1), t),
                LiveScheduler.minutesToCheck(t.minusMinutes(4), t, 10));
        assertEquals(List.of(t), LiveScheduler.minutesToCheck(null, t, 10));                        // 1º disparo: só agora
        assertEquals(3, LiveScheduler.minutesToCheck(t.minusHours(2), t, 2).size());                 // no máximo 2 para trás
        assertEquals(List.of("metals", "fx", "indices"), new LiveProperties(true, 40, null, null, List.of("metals"), null,
                null, null, null).ordered(List.of("fx", "indices", "metals")));
    }

    @Test
    void saidaPorTempo_doMercadoOuPadrao() {
        var orders = new com.jevforex.core.risk.RiskSettings.Orders(0.01, 0.05, 60, 20, 30, 30, 90, 10, 2, 2.0, 300, null, null);
        var ouro = new com.jevforex.core.risk.RiskSettings.MarketRisk(true, 5, 10, 1, 1.0, 15, 1.5, null);
        assertEquals(15, ouro.closeAfter(orders));
        var global = new com.jevforex.core.risk.RiskSettings.Exits(4.0, 7.0);
        assertEquals(new com.jevforex.core.risk.RiskSettings.Exits(1.5, 7.0), ouro.exits(global));   // alvo vazio = global
        var fx = new com.jevforex.core.risk.RiskSettings.MarketRisk(true, 5, 10, 1, 1.0);
        assertEquals(60, fx.closeAfter(orders));
        assertEquals(global, fx.exits(global));
    }

    @Test
    void exposicaoUsd_sinalPorParELado() {
        assertEquals(-1, com.jevforex.app.trade.OrderServiceAccess.usdSign("EURUSD", "BUY"));   // compra EUR = vende USD
        assertEquals(1, com.jevforex.app.trade.OrderServiceAccess.usdSign("EURUSD", "SELL"));
        assertEquals(1, com.jevforex.app.trade.OrderServiceAccess.usdSign("USDJPY", "BUY"));
        assertEquals(0, com.jevforex.app.trade.OrderServiceAccess.usdSign("EURGBP", "BUY"));
    }
}
