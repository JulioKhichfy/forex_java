package com.jevforex.core;

/**
 * Mercados operados. Cada mercado tem modelo, perguntas ao Jev e orçamento de risco próprios
 * (documento mestre, capítulo 20).
 */
public enum Market {
    FX("fx"),
    METALS("metals"),
    CRYPTO("crypto");

    private final String code;

    Market(String code) {
        this.code = code;
    }

    /** Código usado no lake (market=fx), nas tabelas e na configuração. */
    public String code() {
        return code;
    }

    public static Market fromCode(String code) {
        for (Market m : values()) {
            if (m.code.equalsIgnoreCase(code) || m.name().equalsIgnoreCase(code)) {
                return m;
            }
        }
        throw new IllegalArgumentException("Mercado desconhecido: " + code);
    }
}
