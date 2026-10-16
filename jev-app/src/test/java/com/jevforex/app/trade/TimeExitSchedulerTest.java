package com.jevforex.app.trade;

import com.jevforex.core.risk.RiskSettings;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TimeExitSchedulerTest {

    /** Prorrogação ligada: lucro mínimo US$ 0,30, garante 50%, busca +50%, janela 10 min, teto 60, passo 10%. */
    private static final RiskSettings.MoneyExits M = new RiskSettings.MoneyExits(false, 5.0, 10.0, false, null, null, null,
            true, 0.30, 50.0, 50.0, 10, 60, 10.0, null, null, null, null);

    // ouro 0,01 lote: 1,0 de preço = US$ 1; passo 0,001
    private static final double PER = 1.0, TICK = 0.001, MIN = 0.01;

    @Test
    void compraComLucro_stopGaranteMetade_alvoBuscaMaisMetade() {
        // aberta a 4147,000; bid 4149,000 → lucro US$ 2,00
        var l = TimeExitScheduler.plan("BUY", 4147.0, 4134.465, 4149.0, 4149.2, PER, MIN, TICK, M);
        assertEquals(2.0, l.profitUsd(), 1e-9);
        assertEquals(4148.0, l.sl(), 1e-9, "garante US$ 1,00");
        assertEquals(4150.0, l.tp(), 1e-9, "busca +US$ 1,00 além do bid");
    }

    @Test
    void vendaComLucro_espelhada() {
        // vendida a 1,10000; ask 1,09900 → lucro 0,001 × 1.000 (0,01 lote EURUSD: US$ 1.000 por 1,0) = US$ 1,00
        var l = TimeExitScheduler.plan("SELL", 1.10000, 1.10300, 1.09890, 1.09900, 1000, 0.00002, 0.00001, M);
        assertEquals(1.0, l.profitUsd(), 1e-9);
        assertEquals(1.09950, l.sl(), 1e-12);
        assertEquals(1.09850, l.tp(), 1e-12);
    }

    @Test
    void semLucroSuficiente_fechaComoSempre() {
        var l = TimeExitScheduler.plan("BUY", 4147.0, null, 4147.2, 4147.4, PER, MIN, TICK, M);
        assertNull(l.sl());
        assertTrue(l.why().contains("abaixo de US$ 0.30"), l.why());
        assertNull(TimeExitScheduler.plan("SELL", 4147.0, null, 4147.0, 4147.2, PER, MIN, TICK, M).sl(), "no prejuízo");
    }

    @Test
    void stopDoTrailingMelhor_ficaOdoTrailing() {
        // o trailing já garantiu 4148,500 (US$ 1,50): a prorrogação não piora
        var l = TimeExitScheduler.plan("BUY", 4147.0, 4148.5, 4149.0, 4149.2, PER, MIN, TICK, M);
        assertEquals(4148.5, l.sl(), 1e-9);
    }

    @Test
    void minimoDaCorretoraImpedeStopAlemDaEntrada_fecha() {
        // lucro US$ 0,40, mas a corretora exige o stop a 1,0 do preço: ficaria abaixo da entrada
        var l = TimeExitScheduler.plan("BUY", 4147.0, null, 4147.4, 4147.6, PER, 1.0, TICK, M);
        assertNull(l.sl());
        assertTrue(l.why().contains("mínimo da corretora"), l.why());
    }

    @Test
    void padroesDaProrrogacao_desligada() {
        RiskSettings.MoneyExits off = RiskSettings.MoneyExits.OFF;
        assertEquals(false, off.timeLockEnabled());
        assertEquals(10, off.timeLockMinutes());
        assertEquals(60, off.timeLockMaxMinutes());
        assertEquals(50.0, off.timeLockKeepPct(), 1e-12);
    }
    @Test
    void janelaRenova_semPassarDoTeto() {
        java.time.Instant t0 = java.time.Instant.parse("2026-10-15T17:00:00Z");
        assertEquals(java.time.Instant.parse("2026-10-15T17:10:00Z"), TimeExitScheduler.deadline(t0, t0, M), "início: 10 min");
        java.time.Instant later = t0.plusSeconds(30 * 60);
        assertEquals(java.time.Instant.parse("2026-10-15T17:40:00Z"), TimeExitScheduler.deadline(later, t0, M), "nova máxima renova");
        java.time.Instant nearCap = t0.plusSeconds(55 * 60);
        assertEquals(java.time.Instant.parse("2026-10-15T18:00:00Z"), TimeExitScheduler.deadline(nearCap, t0, M), "teto de 60 min");
    }

    @Test
    void novaMaxima_soComOPasso() {
        assertTrue(TimeExitScheduler.isNewPeak(2.20, 2.00, M), "+10%");
        assertTrue(!TimeExitScheduler.isNewPeak(2.15, 2.00, M), "+7,5%: ainda não");
        assertTrue(!TimeExitScheduler.isNewPeak(1.50, 2.00, M), "recuou");
    }

    @Test
    void novaMaxima_stopSobeEAlvoVaiAFrente() {
        // máxima anterior US$ 2 (stop 4148,000); agora bid 4151,000 → lucro US$ 4: stop garante US$ 2, alvo +US$ 2
        var l = TimeExitScheduler.plan("BUY", 4147.0, 4148.0, 4151.0, 4151.2, PER, MIN, TICK, M);
        assertEquals(4149.0, l.sl(), 1e-9);
        assertEquals(4153.0, l.tp(), 1e-9);
    }
}