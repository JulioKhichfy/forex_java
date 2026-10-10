package com.jevforex.core.risk;

import com.jevforex.core.Market;

import java.util.Map;

/**
 * Todas as regras de risco que você aceita, em um só objeto.
 * Vem do bloco {@code trading.risk} do application.yml.
 *
 * <p>Regra geral: quando existe limite em % e em US$, vale o MENOR dos dois.</p>
 */
public record RiskSettings(Global global, Map<Market, MarketRisk> markets, Exits exits, Orders orders,
                           Map<String, LossProfile> lossProfiles) {

    /** Perfil de perda implícito: os limites de {@code global}. */
    public static final String DEFAULT_PROFILE = "padrao";

    public RiskSettings {
        if (global == null) throw new IllegalArgumentException("trading.risk.global é obrigatório");
        if (exits == null) throw new IllegalArgumentException("trading.risk.exits é obrigatório");
        if (orders == null) throw new IllegalArgumentException("trading.risk.orders é obrigatório");
        markets = markets == null ? Map.of() : Map.copyOf(markets);
        java.util.LinkedHashMap<String, LossProfile> p = new java.util.LinkedHashMap<>();
        p.put(DEFAULT_PROFILE, new LossProfile("Padrão", global.maxDailyLossPct(), global.maxWeeklyLossPct()));
        if (lossProfiles != null) {
            lossProfiles.forEach((k, v) -> {
                if (DEFAULT_PROFILE.equals(k)) {
                    throw new IllegalArgumentException("loss-profiles." + k + ": o perfil padrão vem de global");
                }
                p.put(k, v);
            });
        }
        lossProfiles = java.util.Collections.unmodifiableMap(p);
    }

    /** Limites de perda do perfil escolhido (nome desconhecido = falha, não cai no padrão em silêncio). */
    public LossProfile lossProfile(String name) {
        LossProfile p = lossProfiles.get(name == null ? DEFAULT_PROFILE : name);
        if (p == null) throw new IllegalArgumentException("Perfil de perda desconhecido: " + name + " " + lossProfiles.keySet());
        return p;
    }

    /**
     * Perfil de perda escolhível no dashboard (trading.risk.loss-profiles). Os números ficam no yml; o dashboard
     * só escolhe qual vale.
     *
     * @param label            nome mostrado no dashboard
     * @param maxDailyLossPct  perda no dia que bloqueia entradas novas até 00:00 UTC
     * @param maxWeeklyLossPct perda na semana que bloqueia até segunda-feira
     */
    public record LossProfile(String label, double maxDailyLossPct, double maxWeeklyLossPct) {
        public LossProfile {
            if (!(maxDailyLossPct > 0 && maxDailyLossPct <= 100) || !(maxWeeklyLossPct > 0 && maxWeeklyLossPct <= 100)) {
                throw new IllegalArgumentException("loss-profiles: limites devem estar entre 0 e 100%");
            }
        }
    }

    public MarketRisk forMarket(Market market) {
        MarketRisk r = markets.get(market);
        if (r == null) {
            throw new IllegalArgumentException("Sem configuração de risco para o mercado " + market.code()
                    + " (trading.risk.markets." + market.code() + ")");
        }
        return r;
    }

    /**
     * Saídas de toda operação (documento mestre, capítulo 12): enviadas ao servidor junto com a ordem.
     *
     * @param stopAtr distância do stop em múltiplos do ATR(14) M15 (1 R)
     * @param targetR alvo em múltiplos do stop
     */
    public record Exits(double stopAtr, double targetR) {
        public Exits {
            if (!(stopAtr > 0)) throw new IllegalArgumentException("exits.stop-atr deve ser > 0");
            if (!(targetR > 0)) throw new IllegalArgumentException("exits.target-r deve ser > 0");
        }
    }

    /**
     * Travas de cada ordem enviada ao MT5 (passo 6c). O dashboard só escolhe o lote, dentro de [lote mínimo, maxLot].
     *
     * @param defaultLot           lote inicial do tíquete (o dashboard pode mudar, até maxLot)
     * @param maxLot               maior lote aceito numa ordem, qualquer que seja o pedido
     * @param closeAfterMinutes    saída por tempo (0 = só SL/TP); 60 = horizonte do modelo principal
     * @param deviationPoints      desvio máximo de preço na execução (pontos)
     * @param validitySeconds      o EA recusa a ordem depois disso (o preço do clique já ficou velho)
     * @param quoteMaxAgeSeconds   cotação do EA mais velha que isso = sem ordem
     * @param atrMaxAgeMinutes     ATR (da última previsão) mais velho que isso = sem ordem
     * @param blackoutBeforeMinutes sem entrada nos N min antes de evento de importância alta
     * @param blackoutAfterMinutes  … nem nos N min depois
     * @param maxSpreadRatio       spread atual ÷ spread típico do horário acima disso = sem ordem (gate 3)
     * @param minMarginLevelPct    nível de margem (equity ÷ margem) abaixo disso = sem entrada nova
     */
    public record Orders(double defaultLot, double maxLot, int closeAfterMinutes, int deviationPoints,
                         int validitySeconds, int quoteMaxAgeSeconds, int atrMaxAgeMinutes, int blackoutBeforeMinutes,
                         int blackoutAfterMinutes, double maxSpreadRatio, double minMarginLevelPct) {
        public Orders {
            if (!(defaultLot > 0) || maxLot < defaultLot) {
                throw new IllegalArgumentException("orders: precisa 0 < default-lot <= max-lot");
            }
            if (closeAfterMinutes < 0 || deviationPoints < 0 || validitySeconds < 5 || quoteMaxAgeSeconds < 1
                    || atrMaxAgeMinutes < 1 || blackoutBeforeMinutes < 0 || blackoutAfterMinutes < 0) {
                throw new IllegalArgumentException("orders: tempos/desvio inválidos");
            }
            if (!(maxSpreadRatio >= 1)) throw new IllegalArgumentException("orders.max-spread-ratio deve ser >= 1");
            if (minMarginLevelPct < 100) throw new IllegalArgumentException("orders.min-margin-level-pct deve ser >= 100");
        }
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
