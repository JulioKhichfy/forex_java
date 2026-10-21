package com.jevforex.app.trade;

/**
 * Moeda da conta MT5 → US$ (21/10/2026: conta Standard Cent da Exness, em USC = centavos de dólar). Todo o sistema
 * calcula risco em US$ — tetos, stop em US$, trailing, proteção, placar —, então os valores que o MT5 manda em USC
 * (saldo, patrimônio, valor do tick, resultado de posições e fechamentos) são convertidos na fronteira.
 */
public final class AccountCurrency {

    private AccountCurrency() {
    }

    /** Quanto vale 1 unidade da moeda da conta em US$: USC = 0,01; USD (ou desconhecida) = 1. */
    public static double toUsd(String currency) {
        return currency != null && currency.trim().equalsIgnoreCase("USC") ? 0.01 : 1.0;
    }

    public static Double toUsd(Double value, String currency) {
        return value == null ? null : value * toUsd(currency);
    }
}
