package com.jevforex.app.trade;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jevforex.core.risk.RiskSettings;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TrailingServiceTest {

    /** Trailing: começa com US$ 2 de lucro, deixa US$ 2 para trás, anda de US$ 0,50 em US$ 0,50. */
    private static final RiskSettings.MoneyExits M = new RiskSettings.MoneyExits(false, 5.0, 10.0, true, 2.0, 2.0, 0.5);
    /** EURUSD 0,01 lote: US$ 1.000 por 1,0 de preço (US$ 0,10 por pip). */
    private static final double PER_PRICE = 1000;
    private static final double TICK = 0.00001;

    @Test
    void lucroTravado_zeroAZeroNoInicio_depoisSegue() {
        assertNull(M.lockedProfit(1.99), "antes de US$ 2 não mexe");
        assertEquals(0.0, M.lockedProfit(2.0), 1e-12, "com US$ 2: zero a zero");
        assertEquals(3.0, M.lockedProfit(5.0), 1e-12, "com US$ 5: trava US$ 3");
        var curto = new RiskSettings.MoneyExits(false, 5.0, 10.0, true, 2.0, 1.0, 0.5);
        assertEquals(1.0, curto.lockedProfit(2.0), 1e-12, "distância menor que o início: já trava lucro");
        assertThrows(IllegalArgumentException.class, () -> new RiskSettings.MoneyExits(true, 0.0, 10.0, false, null, null, null));
        assertFalse(RiskSettings.MoneyExits.OFF.enabled());
    }

    @Test
    void compra_stopVaiParaOZeroDepoisSobe_eSoAnda_aFavor() {
        double open = 1.10000;
        // lucro de US$ 1,50: ainda não
        assertNull(TrailingService.newStop("BUY", open, 1.09900, 1.10150, 1.10160, PER_PRICE, 0, TICK, M));
        // lucro de US$ 2 (20 pips): stop no preço de entrada
        assertEquals(1.10000, TrailingService.newStop("BUY", open, 1.09900, 1.10200, 1.10210, PER_PRICE, 0, TICK, M), 1e-9);
        // lucro de US$ 5: stop trava US$ 3 (30 pips acima da entrada)
        assertEquals(1.10300, TrailingService.newStop("BUY", open, 1.10000, 1.10500, 1.10510, PER_PRICE, 0, TICK, M), 1e-9);
        // melhora de só US$ 0,30 sobre o stop atual: espera o passo de US$ 0,50
        assertNull(TrailingService.newStop("BUY", open, 1.10270, 1.10500, 1.10510, PER_PRICE, 0, TICK, M));
        // preço voltou: o stop não desce
        assertNull(TrailingService.newStop("BUY", open, 1.10300, 1.10400, 1.10410, PER_PRICE, 0, TICK, M));
    }

    @Test
    void venda_espelhada_eDistanciaMinimaDaCorretora() {
        double open = 1.10000;
        // venda com lucro de US$ 5 (ask 50 pips abaixo): stop 30 pips abaixo da entrada
        assertEquals(1.09700, TrailingService.newStop("SELL", open, 1.10100, 1.09490, 1.09500, PER_PRICE, 0, TICK, M), 1e-9);
        // compra com lucro de US$ 2, mas a corretora exige 30 pips do preço: stop no limite (abaixo da entrada)
        assertEquals(1.09900, TrailingService.newStop("BUY", open, 1.09800, 1.10200, 1.10210, PER_PRICE, 0.00300, TICK, M), 1e-9);
    }

    @Test
    void versaoDoEa_modifyDesde22() {
        var t = new TrailingService(null, null, null, new ObjectMapper());
        assertFalse(t.eaSupportsModify("{\"ea_version\": \"JevExecutor/2.1\"}"));
        assertTrue(t.eaSupportsModify("{\"ea_version\": \"JevExecutor/2.2\"}"));
        assertTrue(t.eaSupportsModify("{\"ea_version\": \"JevExecutor/3.0\"}"));
        assertFalse(t.eaSupportsModify("{}"));
    }
}
