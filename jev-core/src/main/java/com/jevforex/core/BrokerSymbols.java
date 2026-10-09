package com.jevforex.core;

/**
 * Tradução entre o nome canônico do símbolo (EURUSD) e o nome na corretora (EURUSDm na Exness).
 *
 * <p>Internamente, o sistema inteiro usa o nome canônico: lake, tabelas, configuração, modelo.
 * O sufixo só existe na fronteira com o MT5 (documento mestre, capítulo 13).</p>
 *
 * @param suffix sufixo da corretora (ex.: "m"); vazio quando a corretora usa o nome puro
 */
public record BrokerSymbols(String suffix) {

    public BrokerSymbols {
        suffix = suffix == null ? "" : suffix.trim();
    }

    /** EURUSD → EURUSDm */
    public String toBroker(String canonical) {
        return canonical + suffix;
    }

    /** EURUSDm → EURUSD. Recusa símbolo sem o sufixo esperado (falhar fechado: pode ser outra corretora). */
    public String toCanonical(String broker) {
        if (broker == null || broker.isBlank()) throw new IllegalArgumentException("símbolo vazio");
        if (suffix.isEmpty()) return broker;
        if (!broker.endsWith(suffix) || broker.length() == suffix.length()) {
            throw new IllegalArgumentException("Símbolo " + broker + " não tem o sufixo esperado \"" + suffix
                    + "\" (mt5.symbol-suffix)");
        }
        return broker.substring(0, broker.length() - suffix.length());
    }
}
