package com.jevforex.features.scan;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class MomentumRuleTest {

    private static final LocalDateTime T0 = LocalDateTime.parse("2026-10-01T00:00:00");

    /** 800 horas alternando +0,1% e −0,1%, com amplitude de 20 (preço ~100.000). */
    private static List<Bar> calm() {
        List<Bar> out = new ArrayList<>();
        double p = 100_000;
        for (int i = 0; i < 800; i++) {
            double c = p * (i % 2 == 0 ? 1.001 : 0.999);
            out.add(new Bar(T0.plusHours(i), p, Math.max(p, c) + 10, Math.min(p, c) - 10, c, 1, true));
            p = c;
        }
        return out;
    }

    @Test
    void quantil_igualAoDoNumpy() {
        // numpy.quantile([1, 2, 3, 4, 5], 0.98) = 4.92 (interpolação linear)
        assertEquals(4.92, MomentumRule.quantile(new double[]{5, 1, 3, 2, 4}, 0.98), 1e-12);
        assertEquals(1.0, MomentumRule.quantile(new double[]{1, 2, 3}, 0.0), 1e-12);
        assertEquals(3.0, MomentumRule.quantile(new double[]{1, 2, 3}, 1.0), 1e-12);
    }

    @Test
    void horaGrande_seguirOMovimento() {
        List<Bar> h = calm();
        assertNull(MomentumRule.evaluate(h, 720, 2, 14), "hora normal: sem sinal");
        // a última hora cai 1%: muito acima de todas as anteriores → VENDA
        double p = h.get(h.size() - 1).close();
        h.add(new Bar(T0.plusHours(800), p, p + 5, p * 0.988, p * 0.99, 1, true));
        MomentumRule.Signal s = MomentumRule.evaluate(h, 720, 2, 14);
        assertNotNull(s);
        assertEquals("SELL", s.side());
        assertEquals(T0.plusHours(800), s.barStart());
        assertEquals(Math.log(0.99), s.ret(), 1e-12);
        assertEquals(0.001, s.threshold(), 1e-4, "limiar = o tamanho das horas anteriores (todas ~0,1%)");
        assertEquals(true, s.atr() > 0);
    }

    @Test
    void poucasBarras_semSinal() {
        assertNull(MomentumRule.evaluate(calm().subList(0, 700), 720, 2, 14), "menos que o lookback + 1");
    }

    @Test
    void atr_mediaDoTrueRange() {
        List<Bar> h = new ArrayList<>();
        for (int i = 0; i < 16; i++) h.add(new Bar(T0.plusHours(i), 100, 102, 99, 100, 1, true));   // TR = 3
        h.add(new Bar(T0.plusHours(16), 100, 101, 90, 95, 1, true));   // TR = 11
        assertEquals((13 * 3 + 11) / 14.0, MomentumRule.atr(h, 14), 1e-12);
    }
}
