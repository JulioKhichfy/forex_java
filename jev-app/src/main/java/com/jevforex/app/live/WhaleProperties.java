package com.jevforex.app.live;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Baleias no bitcoin (Radar, 25/10/2026; {@link com.jevforex.features.scan.WhaleRule}). */
@ConfigurationProperties(prefix = "whales")
public record WhaleProperties(Boolean enabled, String binanceSymbol, String symbol, String market, Double multiple,
                              Double minImbalance, Integer lookbackMinutes, String url) {

    public WhaleProperties {
        enabled = enabled == null || enabled;
        binanceSymbol = binanceSymbol == null ? "BTCUSDT" : binanceSymbol;
        symbol = symbol == null ? "BTCUSD" : symbol;
        market = market == null ? "crypto" : market;
        multiple = multiple == null ? 10.0 : multiple;
        minImbalance = minImbalance == null ? 0.5 : minImbalance;
        lookbackMinutes = lookbackMinutes == null ? 1440 : lookbackMinutes;
        url = url == null ? "https://fapi.binance.com" : url;
    }
}
