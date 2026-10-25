package com.jevforex.app.trade;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * Momentum BTC (passo 12b, 25/10/2026): a regra e as saídas em que foi validada (tools/estudo_momentum.py), e o filtro de
 * fluxo (passo 12e, tools/estudo_fluxo.py). Mudar qualquer número = outra regra: refaça o estudo antes.
 *
 * @param flowFilter só entra com o fluxo agressor da hora (futuros da Binance) a favor ≥ {@code flowMin}
 */
@ConfigurationProperties(prefix = "momentum")
public record MomentumProperties(String market, List<String> symbols, Integer lookbackHours, Double topPct,
                                 Integer atrPeriod, Double stopAtr, Double targetR, Integer closeAfterMinutes,
                                 Integer maxEntryDelaySeconds, Boolean flowFilter, Double flowMin, String flowSymbol,
                                 String flowUrl) {

    public MomentumProperties {
        market = market == null ? "crypto" : market;
        symbols = symbols == null ? List.of("BTCUSD") : List.copyOf(symbols);
        lookbackHours = lookbackHours == null ? 720 : lookbackHours;
        topPct = topPct == null ? 2.0 : topPct;
        atrPeriod = atrPeriod == null ? 14 : atrPeriod;
        stopAtr = stopAtr == null ? 1.5 : stopAtr;
        targetR = targetR == null ? 1.5 : targetR;
        closeAfterMinutes = closeAfterMinutes == null ? 1440 : closeAfterMinutes;
        maxEntryDelaySeconds = maxEntryDelaySeconds == null ? 300 : maxEntryDelaySeconds;
        flowFilter = flowFilter == null || flowFilter;
        flowMin = flowMin == null ? 0.089 : flowMin;
        flowSymbol = flowSymbol == null ? "BTCUSDT" : flowSymbol;
        flowUrl = flowUrl == null ? "https://fapi.binance.com" : flowUrl;
    }
}
