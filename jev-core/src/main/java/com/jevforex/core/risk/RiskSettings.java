package com.jevforex.core.risk;

import com.jevforex.core.Market;

import java.util.Map;

/**
 * Todas as regras de risco que você aceita, em um só objeto.
 * Vem do bloco {@code trading.risk} do application.yml.
 *
 * <p>Regra geral: quando existe limite em % e em US$, vale o MENOR dos dois.</p>
 */
public record RiskSettings(Global global, Map<Market, MarketRisk> markets) {

    public RiskSettings {
        if (global == null) throw new IllegalArgumentException("trading.risk.global é obrigatório");
        markets = markets == null ? Map.of() : Map.copyOf(markets);
    }

    public MarketRisk forMarket(Market market) {
        MarketRisk r = markets.get(market);
        if (r == null) {
            throw new IllegalArgumentException("Sem configuração de risco para o mercado " + market.code()
                    + " (trading.risk.markets." + market.code() + ")");
        }
        return r;
    }

    /** O que fazer quando nem o lote mínimo cabe no risco-alvo do trade. */
    public enum MinLotPolicy {
        /** Não opera. É o comportamento mais seguro. */
        SKIP,
        /** Opera com o lote mínimo, desde que o risco não passe do teto global por trade. */
        ALLOW_UP_TO_CAP
    }

    /**
     * Limites que valem para a conta inteira, somando todos os mercados.
     *
     * @param maxRiskPerTradePct    teto absoluto por trade, em % do saldo (inclui o efeito do lote mínimo)
     * @param maxRiskPerTradeUsd    teto absoluto por trade em US$ (0 = sem teto em dinheiro)
     * @param maxOpenRiskPct        soma do risco de todas as posições abertas
     * @param maxDailyLossPct       perda no dia que bloqueia novas entradas até 00:00 UTC
     * @param maxWeeklyLossPct      perda na semana que bloqueia novas entradas até segunda
     * @param maxPositions          posições simultâneas na conta
     * @param maxUsdNetExposurePct  exposição líquida a USD somando os mercados (capítulo 20)
     * @param minLotPolicy          ver {@link MinLotPolicy}
     */
    public record Global(
            double maxRiskPerTradePct,
            double maxRiskPerTradeUsd,
            double maxOpenRiskPct,
            double maxDailyLossPct,
            double maxWeeklyLossPct,
            int maxPositions,
            double maxUsdNetExposurePct,
            MinLotPolicy minLotPolicy) {

        public Global {
            requirePct("global.max-risk-per-trade-pct", maxRiskPerTradePct);
            requirePct("global.max-open-risk-pct", maxOpenRiskPct);
            requirePct("global.max-daily-loss-pct", maxDailyLossPct);
            requirePct("global.max-weekly-loss-pct", maxWeeklyLossPct);
            requirePct("global.max-usd-net-exposure-pct", maxUsdNetExposurePct);
            if (maxRiskPerTradeUsd < 0) throw new IllegalArgumentException("global.max-risk-per-trade-usd < 0");
            if (maxPositions < 1) throw new IllegalArgumentException("global.max-positions deve ser >= 1");
            if (minLotPolicy == null) minLotPolicy = MinLotPolicy.SKIP;
        }

        /** Teto por trade em US$ para um saldo: o menor entre % e valor fixo. */
        public double maxRiskPerTradeMoney(double balance) {
            double byPct = balance * maxRiskPerTradePct / 100.0;
            return maxRiskPerTradeUsd > 0 ? Math.min(byPct, maxRiskPerTradeUsd) : byPct;
        }
    }

    /**
     * Orçamento de um mercado.
     *
     * @param enabled          se o mercado pode operar
     * @param riskPerTradePct  risco-ALVO por trade, em % do saldo
     * @param maxOpenRiskPct   risco aberto máximo neste mercado
     * @param maxPositions     posições simultâneas neste mercado
     * @param usdWeight        peso na exposição líquida a USD (1.0 = conta integralmente)
     */
    public record MarketRisk(
            boolean enabled,
            double riskPerTradePct,
            double maxOpenRiskPct,
            int maxPositions,
            double usdWeight) {

        public MarketRisk {
            requirePct("risk-per-trade-pct", riskPerTradePct);
            requirePct("max-open-risk-pct", maxOpenRiskPct);
            if (maxPositions < 0) throw new IllegalArgumentException("max-positions < 0");
            if (usdWeight < 0) throw new IllegalArgumentException("usd-weight < 0");
        }
    }

    private static void requirePct(String name, double v) {
        if (v < 0 || v > 100) {
            throw new IllegalArgumentException(name + " deve estar entre 0 e 100 (recebido " + v + ")");
        }
    }
}
