package com.jevforex.features.scan;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TripleTopTest {

    private static final LocalDateTime T0 = LocalDateTime.of(2026, 10, 20, 0, 0);

    /** Fechamentos → barras de M5 com máxima/mínima a ±0,2 do fechamento. */
    private static List<Bar> bars(List<Double> closes) {
        List<Bar> out = new ArrayList<>();
        double prev = closes.get(0);
        for (int i = 0; i < closes.size(); i++) {
            double c = closes.get(i);
            out.add(new Bar(T0.plusMinutes(5L * i), prev, Math.max(prev, c) + 0.2, Math.min(prev, c) - 0.2, c, 100, true));
            prev = c;
        }
        return out;
    }

    private static void line(List<Double> c, double to, int steps) {
        double from = c.get(c.size() - 1);
        for (int i = 1; i <= steps; i++) c.add(from + (to - from) * i / steps);
    }

    /** Parado em 100, sobe até 108 e 112 (1º topo), cai a 110, 112 (2º), 110, 111,9 (3º) e depois o que vier. */
    private static List<Double> threeTops() {
        List<Double> c = new ArrayList<>();
        for (int i = 0; i < 60; i++) c.add(100.0);
        line(c, 108, 40);
        line(c, 112, 8);
        line(c, 110, 4);
        line(c, 112, 4);
        line(c, 110, 4);
        line(c, 111.9, 4);
        return c;
    }

    @Test
    void tresTopos_rompendoOPescoco() {
        List<Double> c = threeTops();
        line(c, 109.5, 5);   // fecha abaixo do pescoço
        TripleTop.Pattern p = TripleTop.latest(bars(c));
        assertNotNull(p);
        assertTrue(p.broken());
        assertEquals(112.2, p.top(), 1e-9);
        assertTrue(p.neck() < 110 && p.neck() > 109.5, "pescoço = a menor mínima entre os topos: " + p.neck());
        assertNotNull(p.brokeAgo());
    }

    @Test
    void formado_semRomper_eInvalidadoAoPassarDosTopos() {
        List<Double> c = threeTops();
        line(c, 110.6, 3);   // desce, mas fica acima do pescoço
        TripleTop.Pattern p = TripleTop.latest(bars(c));
        assertNotNull(p);
        assertFalse(p.broken());
        line(c, 113, 4);     // passa dos topos: o padrão deixa de valer
        assertNull(TripleTop.latest(bars(c)));
    }

    @Test
    void semAltaAntes_naoEhTresTopos() {
        List<Double> c = new ArrayList<>();
        for (int i = 0; i < 100; i++) c.add(111.0);   // lateral, sem a alta antes do 1º topo
        line(c, 112, 4);
        line(c, 110, 4);
        line(c, 112, 4);
        line(c, 110, 4);
        line(c, 111.9, 4);
        line(c, 109.5, 5);
        assertNull(TripleTop.latest(bars(c)));
    }
}
