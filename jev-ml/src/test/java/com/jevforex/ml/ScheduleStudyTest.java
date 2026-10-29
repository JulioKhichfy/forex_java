package com.jevforex.ml;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ScheduleStudyTest {

    private static final long MONTH = (long) (30.44 * 86_400);

    @Test
    void pesoAoRecente_repeteAsLinhasNovas() {
        assertEquals(1, ScheduleStudy.copies(0, null), "sem peso: uma vez");
        assertEquals(3, ScheduleStudy.copies(0, 6.0), "a mais nova: 3×");
        assertEquals(2, ScheduleStudy.copies(3 * MONTH, 6.0));
        assertEquals(2, ScheduleStudy.copies(6 * MONTH, 6.0), "meia-vida: 1,5 → 2");
        assertEquals(1, ScheduleStudy.copies(12 * MONTH, 6.0));
        assertEquals(1, ScheduleStudy.copies(24 * MONTH, 6.0), "nunca menos de uma");
    }

    @Test
    void receitas_aAtualVemPrimeiro() {
        ScheduleStudy.Recipe atual = ScheduleStudy.defaultRecipes().get(0);
        assertEquals(24, atual.trainMonths());
        assertEquals(false, atual.withExtra());
        assertEquals(30, atual.stepDays());
    }
}
