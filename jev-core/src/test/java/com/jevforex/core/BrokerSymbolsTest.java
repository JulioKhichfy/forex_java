package com.jevforex.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class BrokerSymbolsTest {

    private final BrokerSymbols exness = new BrokerSymbols("m");

    @Test
    void exness_adicionaERemoveSufixo() {
        assertEquals("EURUSDm", exness.toBroker("EURUSD"));
        assertEquals("EURUSD", exness.toCanonical("EURUSDm"));
        assertEquals("XAUUSD", exness.toCanonical("XAUUSDm"));
    }

    @Test
    void semSufixo_recusa() {
        // EURUSD sem o "m" pode ser de outro terminal/corretora: não adivinha
        assertThrows(IllegalArgumentException.class, () -> exness.toCanonical("EURUSD"));
        assertThrows(IllegalArgumentException.class, () -> exness.toCanonical("m"));
    }

    @Test
    void corretoraSemSufixo_nomeIgual() {
        var pura = new BrokerSymbols(null);
        assertEquals("EURUSD", pura.toBroker("EURUSD"));
        assertEquals("EURUSD", pura.toCanonical("EURUSD"));
    }
}
