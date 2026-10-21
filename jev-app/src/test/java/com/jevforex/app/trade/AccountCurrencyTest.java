package com.jevforex.app.trade;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class AccountCurrencyTest {

    @Test
    void contaCent_emCentavos_viraDolar() {
        assertEquals(0.01, AccountCurrency.toUsd("USC"), 1e-12);
        assertEquals(0.01, AccountCurrency.toUsd(" usc "), 1e-12);
        assertEquals(40.0, AccountCurrency.toUsd(4000.0, "USC"), 1e-9, "4.000 USC = US$ 40");
        assertEquals(1.0, AccountCurrency.toUsd("USD"), 1e-12);
        assertEquals(1.0, AccountCurrency.toUsd(null), 1e-12);
        assertNull(AccountCurrency.toUsd(null, "USC"));
    }
}
