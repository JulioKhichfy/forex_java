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
                           Map<String, LossProfile> lossProfiles, MoneyExits money) {

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
        if (money == null) money = MoneyExits.OFF;
    }

    public RiskSettings(Global global, Map<Market, MarketRisk> markets, Exits exits, Orders orders,
                        Map<String, LossProfile> lossProfiles) {
        this(global, markets, exits, orders, lossProfiles, null);
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

    /** Stop e alvo de um mercado: os de trading.risk.markets.&lt;m&gt; se houver, senão os de exits. */
    public Exits exitsFor(Market market) {
        MarketRisk r = markets.get(market);
        return r == null ? exits : r.exits(exits);
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
     * Stop e alvo em DÓLARES por ordem e trailing stop (página "Stop em US$", pedido do operador em 14/10/2026).
     *
     * <p>Ligado ({@code enabled}), vale POR CIMA do stop/alvo em ATR (exits e os de cada mercado) em toda ordem nova:
     * a distância em preço sai de {@code stop-usd} ÷ (valor de 1,0 de preço × lote). O trailing é independente: com
     * lucro de {@code trail-start-usd}, o stop vai para o zero a zero; depois segue o preço, travando
     * lucro − {@code trail-distance-usd}, só quando melhora pelo menos {@code trail-step-usd}.</p>
     *
     * @param enabled          stop e alvo em US$ no lugar dos de ATR
     * @param stopUsd          perda máxima por ordem, em US$
     * @param takeUsd          ganho no alvo por ordem, em US$
     * @param trailEnabled     move o stop das posições abertas a favor
     * @param trailStartUsd    lucro (US$) a partir do qual o stop começa a andar (vai para o zero a zero)
     * @param trailDistanceUsd quanto do lucro o stop deixa para trás (US$)
     * @param trailStepUsd     melhora mínima (US$) para mover de novo (evita mexer a cada tique)
     * @param timeLockEnabled  prorrogação com lucro (pedido do operador em 15/10/2026): na saída por tempo, posição com
     *                         lucro não fecha a mercado; recebe stop e alvo perto do preço e ganha mais um tempo
     * @param timeLockMinProfitUsd lucro mínimo (US$) para prorrogar; abaixo disso fecha como sempre
     * @param timeLockKeepPct  % do lucro do momento que o novo stop garante (o resto é o que se arrisca)
     * @param timeLockExtraPct o novo alvo fica a esta % do lucro do momento além do preço
     * @param timeLockMinutes  janela da prorrogação: renova a cada nova máxima de lucro; sem nova máxima nesse tempo,
     *                         fecha a mercado
     * @param timeLockMaxMinutes teto da prorrogação (desde o fim do tempo original): no teto, fecha a mercado
     * @param timeLockStepPct  só atualiza stop/alvo (e renova a janela) quando o lucro passa a máxima anterior em
     *                         pelo menos esta %
     */
    public record MoneyExits(Boolean enabled, Double stopUsd, Double takeUsd, Boolean trailEnabled,
                             Double trailStartUsd, Double trailDistanceUsd, Double trailStepUsd,
                             Boolean timeLockEnabled, Double timeLockMinProfitUsd, Double timeLockKeepPct,
                             Double timeLockExtraPct, Integer timeLockMinutes, Integer timeLockMaxMinutes,
                             Double timeLockStepPct) {
        public static final MoneyExits OFF = new MoneyExits(false, null, null, false, null, null, null,
                false, null, null, null, null, null, null);

        public MoneyExits {
            if (enabled == null) enabled = false;
            if (stopUsd == null) stopUsd = 5.0;
            if (takeUsd == null) takeUsd = 10.0;
            if (trailEnabled == null) trailEnabled = false;
            if (trailStartUsd == null) trailStartUsd = 2.0;
            if (trailDistanceUsd == null) trailDistanceUsd = trailStartUsd;
            if (trailStepUsd == null) trailStepUsd = 0.5;
            if (!(stopUsd > 0)) throw new IllegalArgumentException("money.stop-usd deve ser > 0");
            if (!(takeUsd > 0)) throw new IllegalArgumentException("money.take-usd deve ser > 0");
            if (!(trailStartUsd > 0)) throw new IllegalArgumentException("money.trail-start-usd deve ser > 0");
            if (!(trailDistanceUsd > 0)) throw new IllegalArgumentException("money.trail-distance-usd deve ser > 0");
            if (trailStepUsd < 0) throw new IllegalArgumentException("money.trail-step-usd deve ser >= 0");
            if (timeLockEnabled == null) timeLockEnabled = false;
            if (timeLockMinProfitUsd == null) timeLockMinProfitUsd = 0.30;
            if (timeLockKeepPct == null) timeLockKeepPct = 50.0;
            if (timeLockExtraPct == null) timeLockExtraPct = 50.0;
            if (timeLockMinutes == null) timeLockMinutes = 10;
            if (timeLockMaxMinutes == null) timeLockMaxMinutes = 60;
            if (timeLockStepPct == null) timeLockStepPct = 10.0;
            if (!(timeLockMinProfitUsd > 0)) throw new IllegalArgumentException("money.time-lock-min-profit-usd deve ser > 0");
            if (!(timeLockKeepPct > 0 && timeLockKeepPct < 100)) {
                throw new IllegalArgumentException("money.time-lock-keep-pct deve estar entre 0 e 100 (exclusive)");
            }
            if (!(timeLockExtraPct > 0)) throw new IllegalArgumentException("money.time-lock-extra-pct deve ser > 0");
            if (timeLockMinutes < 1) throw new IllegalArgumentException("money.time-lock-minutes deve ser >= 1");
            if (timeLockMaxMinutes < timeLockMinutes) {
                throw new IllegalArgumentException("money.time-lock-max-minutes deve ser >= time-lock-minutes");
            }
            if (!(timeLockStepPct > 0)) throw new IllegalArgumentException("money.time-lock-step-pct deve ser > 0");
        }

        /**
         * Lucro que o stop deve travar com o lucro atual: nada antes do início; depois lucro − distância, nunca abaixo
         * de zero (no início, com distância = início, é o zero a zero).
         *
         * @return null = o trailing ainda não começou
         */
        public Double lockedProfit(double profitUsd) {
            if (profitUsd < trailStartUsd) return null;
            return Math.max(0, profitUsd - trailDistanceUsd);
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
     * @param closeAfterMinutes saída por tempo das ordens deste mercado (null = orders.close-after-minutes;
     *                          0 = só SL/TP). Ex.: ouro 15, o horizonte da regra validada
     * @param stopAtr          stop deste mercado em ATR (null = exits.stop-atr)
     * @param targetR          alvo deste mercado em múltiplos do stop (null = exits.target-r)
     */
    public record MarketRisk(
            boolean enabled,
            double riskPerTradePct,
            double maxOpenRiskPct,
            int maxPositions,
            double usdWeight,
            Integer closeAfterMinutes,
            Double stopAtr,
            Double targetR) {

        public MarketRisk {
            requirePct("risk-per-trade-pct", riskPerTradePct);
            requirePct("max-open-risk-pct", maxOpenRiskPct);
            if (maxPositions < 0) throw new IllegalArgumentException("max-positions < 0");
            if (usdWeight < 0) throw new IllegalArgumentException("usd-weight < 0");
            if (closeAfterMinutes != null && closeAfterMinutes < 0) {
                throw new IllegalArgumentException("close-after-minutes < 0");
            }
            if (stopAtr != null && !(stopAtr > 0)) throw new IllegalArgumentException("stop-atr do mercado deve ser > 0");
            if (targetR != null && !(targetR > 0)) throw new IllegalArgumentException("target-r do mercado deve ser > 0");
        }

        public MarketRisk(boolean enabled, double riskPerTradePct, double maxOpenRiskPct, int maxPositions,
                          double usdWeight) {
            this(enabled, riskPerTradePct, maxOpenRiskPct, maxPositions, usdWeight, null, null, null);
        }

        /** Stop e alvo que valem para este mercado (os dele ou os de exits). */
        public Exits exits(Exits global) {
            return new Exits(stopAtr != null ? stopAtr : global.stopAtr(), targetR != null ? targetR : global.targetR());
        }

        /** Saída por tempo que vale para este mercado. */
        public int closeAfter(Orders orders) {
            return closeAfterMinutes != null ? closeAfterMinutes : orders.closeAfterMinutes();
        }
    }

    private static void requirePct(String name, double v) {
        if (v < 0 || v > 100) {
            throw new IllegalArgumentException(name + " deve estar entre 0 e 100 (recebido " + v + ")");
        }
    }
}
