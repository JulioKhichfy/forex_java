package com.jevforex.ml;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MetricsTest {

    @Test
    void auc_perfeitaAleatoriaEInvertida() {
        int[] y = {2, 2, 0, 0};
        assertEquals(1.0, Metrics.auc(new double[][]{{0, 0, .9}, {0, 0, .8}, {0, 0, .2}, {0, 0, .1}}, y, 2), 1e-12);
        assertEquals(0.0, Metrics.auc(new double[][]{{0, 0, .1}, {0, 0, .2}, {0, 0, .8}, {0, 0, .9}}, y, 2), 1e-12);
        assertEquals(0.5, Metrics.auc(new double[][]{{0, 0, .5}, {0, 0, .5}, {0, 0, .5}, {0, 0, .5}}, y, 2), 1e-12);
    }

    @Test
    void logLoss_eLinhaDeBase() {
        int[] y = {0, 1, 2};
        double ll = Metrics.logLoss(new double[][]{{1, 0, 0}, {0, 1, 0}, {0, 0, 1}}, y);
        assertEquals(0.0, ll, 1e-9);
        double[][] prior = Metrics.priors(new int[]{0, 1, 2}, 3);
        assertEquals(Math.log(3), Metrics.logLoss(prior, y), 1e-9);   // classes equilibradas: ln 3
    }

    @Test
    void trades_gate4_resultadoEmR_eDrawdown() {
        double[][] p = {
                {0.10, 0.20, 0.70},   // compra (P 0,70, margem 0,60)
                {0.65, 0.25, 0.10},   // venda
                {0.30, 0.20, 0.50},   // fica de fora (P < 0,60)
                {0.05, 0.30, 0.65}};  // compra
        double[] yBuy = {1.5, 0, 0, -3.0};
        double[] ySell = {0, 0.75, 0, 0};
        Metrics.Trades t = Metrics.trades(p, yBuy, ySell, 0.60, 0.35, 1.5, 0.5);
        assertEquals(3, t.n());
        assertEquals((1.0 + 0.5 - 2.0) / 3, t.expectancyR(), 1e-12);   // R = resultado ÷ 1,5 ATR
        assertEquals(1.5 / 2.0, t.profitFactor(), 1e-12);
        assertEquals(2.0 * 0.5, t.maxDrawdownPct(), 1e-12);            // pico 0,75% → −0,25%
        assertEquals(2.0 / 3, t.hitRate(), 1e-12);
    }

    @Test
    void gbm_aprendeRegraSimples() {
        // ALTA quando x0 > 0,5; QUEDA quando x0 < −0,5; senão LATERAL. x1 é ruído.
        Random rnd = new Random(1);
        int n = 3000;
        double[][] x = new double[n][2];
        int[] y = new int[n];
        for (int i = 0; i < n; i++) {
            x[i][0] = rnd.nextGaussian();
            x[i][1] = rnd.nextGaussian();
            y[i] = x[i][0] > 0.5 ? Dataset.UP : x[i][0] < -0.5 ? Dataset.DOWN : Dataset.FLAT;
        }
        Gbm m = Gbm.fit(x, y, List.of("sinal", "ruido"), new ExperimentConfig.Gbm(60, 3, 8, 20, 0.1, 0.8), 7);
        double[][] p = m.predict(x);
        assertTrue(Metrics.logLoss(p, y) < 0.5 * Metrics.logLoss(Metrics.priors(y, n), y));
        assertTrue(Metrics.auc(p, y, Dataset.UP) > 0.95);
        assertTrue(m.importance().get("sinal") > m.importance().get("ruido"));
    }
}
