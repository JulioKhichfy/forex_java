package com.jevforex.app.config;

import com.jevforex.core.Instrument;
import com.jevforex.core.Market;
import com.jevforex.core.risk.RiskSettings;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Bloco {@code trading} do application.yml: modo, saldo de referência, riscos e instrumentos.
 * É o ÚNICO lugar onde você ajusta os riscos que aceita.
 */
@ConfigurationProperties(prefix = "trading")
public record TradingProperties(
        Mode mode,
        Account account,
        Risk risk,
        Map<String, InstrumentConfig> instruments) {

    public enum Mode { SHADOW, DEMO, LIVE }

    public TradingProperties {
        if (mode == null) mode = Mode.SHADOW;
        if (account == null) account = new Account(20.0);
        if (risk == null) throw new IllegalArgumentException("trading.risk não configurado");
        instruments = instruments == null ? Map.of() : new LinkedHashMap<>(instruments);
    }

    /** @param referenceBalanceUsd saldo usado no modo shadow e no comando risk; em demo/live vem do MT5 */
    public record Account(double referenceBalanceUsd) {
    }

    public record Risk(Global global, Map<String, MarketRiskConfig> markets) {
    }

    public record Global(double maxRiskPerTradePct, double maxRiskPerTradeUsd, double maxOpenRiskPct,
                         double maxDailyLossPct, double maxWeeklyLossPct, int maxPositions,
                         double maxUsdNetExposurePct, RiskSettings.MinLotPolicy minLotPolicy) {
    }

    public record MarketRiskConfig(boolean enabled, double riskPerTradePct, double maxOpenRiskPct, int maxPositions,
                                   double usdWeight) {
    }

    public record InstrumentConfig(String market, double tickSize, double tickValuePerLot, double minLot,
                                   double lotStep, double maxLot, double typicalStop) {
    }

    /** Converte para o objeto de domínio, validando os valores. */
    public RiskSettings toRiskSettings() {
        if (risk.global() == null) throw new IllegalArgumentException("trading.risk.global não configurado");
        Global g = risk.global();
        var global = new RiskSettings.Global(g.maxRiskPerTradePct(), g.maxRiskPerTradeUsd(), g.maxOpenRiskPct(),
                g.maxDailyLossPct(), g.maxWeeklyLossPct(), g.maxPositions(), g.maxUsdNetExposurePct(),
                g.minLotPolicy());
        Map<Market, RiskSettings.MarketRisk> m = new EnumMap<>(Market.class);
        if (risk.markets() != null) {
            risk.markets().forEach((code, c) -> m.put(Market.fromCode(code), new RiskSettings.MarketRisk(
                    c.enabled(), c.riskPerTradePct(), c.maxOpenRiskPct(), c.maxPositions(), c.usdWeight())));
        }
        return new RiskSettings(global, m);
    }

    public Map<String, Instrument> toInstruments() {
        Map<String, Instrument> out = new LinkedHashMap<>();
        instruments.forEach((symbol, c) -> out.put(symbol, new Instrument(symbol, Market.fromCode(c.market()),
                c.tickSize(), c.tickValuePerLot(), c.minLot(), c.lotStep(),
                c.maxLot() <= 0 ? 100 : c.maxLot(), c.typicalStop())));
        return out;
    }
}
