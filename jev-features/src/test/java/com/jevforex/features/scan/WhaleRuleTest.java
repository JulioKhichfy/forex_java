package com.jevforex.features.scan;

import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class WhaleRuleTest {

    private static double[] fill(int n, double x) {
        double[] a = new double[n];
        Arrays.fill(a, x);
        return a;
    }

    @Test
    void minutoGrandeDeUmLadoSo_ehBaleia() {
        int n = 1441;
        double[] v = fill(n, 100), tb = fill(n, 50), o = fill(n, 100_000), c = fill(n, 100_000);
        // último minuto: 1.200 BTC, 1.100 de compra agressiva (11× o normal de 100), preço +0,2%
        v[1440] = 1200;
        tb[1440] = 1100;
        c[1440] = 100_200;
        WhaleRule.Whale w = WhaleRule.evaluate(v, tb, o, c, 1440, 1440, 10, 0.5);
        assertNotNull(w);
        assertEquals("BUY", w.side());
        assertEquals(1100, w.volume(), 1e-9);
        assertEquals(11.0, w.multiple(), 1e-9);
        assertEquals(1000.0 / 1200, w.imbalance(), 1e-9);
        assertEquals(20.0, w.moveBps(), 1e-6);
    }

    @Test
    void volumeGrandeMasEquilibrado_ouPequeno_naoEh() {
        int n = 1441;
        double[] v = fill(n, 100), tb = fill(n, 50), o = fill(n, 100), c = fill(n, 100);
        v[1440] = 3000;
        tb[1440] = 1600;   // 1.600 × 1.400: muito volume, mas dos dois lados (desequilíbrio 0,07)
        assertNull(WhaleRule.evaluate(v, tb, o, c, 1440, 1440, 10, 0.5));
        v[1440] = 500;
        tb[1440] = 500;    // todo de um lado, mas só 5× o normal
        assertNull(WhaleRule.evaluate(v, tb, o, c, 1440, 1440, 10, 0.5));
        assertNull(WhaleRule.evaluate(v, tb, o, c, 100, 1440, 10, 0.5), "menos de 24 h de histórico");
    }

    @Test
    void vendaAgressiva_movimentoAFavorDaVenda() {
        int n = 11;
        double[] v = fill(n, 10), tb = fill(n, 5), o = fill(n, 100), c = fill(n, 100);
        v[10] = 200;
        tb[10] = 20;       // 180 de venda agressiva = 18×
        c[10] = 99.5;      // caiu 0,5%: +50 bps a favor da venda
        WhaleRule.Whale w = WhaleRule.evaluate(v, tb, o, c, 10, 10, 10, 0.5);
        assertNotNull(w);
        assertEquals("SELL", w.side());
        assertEquals(50.0, w.moveBps(), 1e-6);
    }
}
