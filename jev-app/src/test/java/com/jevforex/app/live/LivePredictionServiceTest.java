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
    void exposicaoUsd_sinalPorParELado() {
        assertEquals(-1, com.jevforex.app.trade.OrderServiceAccess.usdSign("EURUSD", "BUY"));   // compra EUR = vende USD
        assertEquals(1, com.jevforex.app.trade.OrderServiceAccess.usdSign("EURUSD", "SELL"));
        assertEquals(1, com.jevforex.app.trade.OrderServiceAccess.usdSign("USDJPY", "BUY"));
        assertEquals(0, com.jevforex.app.trade.OrderServiceAccess.usdSign("EURGBP", "BUY"));
    }
}
