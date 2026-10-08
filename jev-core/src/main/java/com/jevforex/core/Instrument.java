package com.jevforex.core;

/**
 * Um símbolo negociável, descrito do mesmo jeito que o MT5 descreve
 * (SYMBOL_TRADE_TICK_SIZE, SYMBOL_TRADE_TICK_VALUE, SYMBOL_VOLUME_MIN, SYMBOL_VOLUME_STEP).
 *
 * <p>No passo 1 esses valores vêm do application.yml (aproximados). A partir do passo 2,
 * o exportador do MT5 envia os valores reais da sua corretora.</p>
 *
 * @param symbol          nome no MT5 (ex.: EURUSD; algumas corretoras usam sufixo, ex.: EURUSD.m)
 * @param market          mercado ao qual pertence
 * @param tickSize        menor variação de preço (ex.: 0.00001 no EURUSD)
 * @param tickValuePerLot valor em US$ de um tick para 1,00 lote
 * @param minLot          lote mínimo (ex.: 0.01)
 * @param lotStep         passo de volume (ex.: 0.01)
 * @param maxLot          lote máximo aceito pela corretora
 * @param typicalStop     distância de stop típica EM PREÇO (ex.: 0.0014 = 14 pips no EURUSD).
 *                        Provisório: a partir do passo 3 o stop vem do ATR.
 */
public record Instrument(
        String symbol,
        Market market,
        double tickSize,
        double tickValuePerLot,
        double minLot,
        double lotStep,
        double maxLot,
        double typicalStop) {

    public Instrument {
        if (symbol == null || symbol.isBlank()) throw new IllegalArgumentException("symbol vazio");
        if (market == null) throw new IllegalArgumentException(symbol + ": market obrigatório");
        if (tickSize <= 0) throw new IllegalArgumentException(symbol + ": tickSize deve ser > 0");
        if (tickValuePerLot <= 0) throw new IllegalArgumentException(symbol + ": tickValuePerLot deve ser > 0");
        if (minLot <= 0) throw new IllegalArgumentException(symbol + ": minLot deve ser > 0");
        if (lotStep <= 0) throw new IllegalArgumentException(symbol + ": lotStep deve ser > 0");
        if (maxLot < minLot) throw new IllegalArgumentException(symbol + ": maxLot < minLot");
    }

    /** Quanto se perde, em US$, com 1,00 lote, se o preço andar {@code priceDistance} contra a posição. */
    public double lossPerLot(double priceDistance) {
        return (priceDistance / tickSize) * tickValuePerLot;
    }
}
