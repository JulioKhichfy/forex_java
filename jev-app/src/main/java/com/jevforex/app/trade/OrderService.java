package com.jevforex.app.trade;

import com.jevforex.app.config.RiskSettingsHolder;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jevforex.app.config.RiskConfig.InstrumentCatalog;
import com.jevforex.app.config.TradingProperties;
import com.jevforex.app.live.PredictionRepository;
import com.jevforex.collect.mt5.Mt5Parsers;
import com.jevforex.collect.mt5.Mt5Repository;
import com.jevforex.core.Instrument;
import com.jevforex.core.Market;
import com.jevforex.core.risk.RiskSettings;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Ordens pelo dashboard (passo 6c). O operador escolhe par, lado e lote; ESTA classe confere cada trava antes de a
 * ordem existir (falhar fechado: qualquer dado faltando ou velho = sem ordem) e o EA executa com SL/TP no servidor.
 *
 * <p>Stop e alvo saem como DISTÂNCIA em preço (stop = stop-atr × ATR, alvo = target-r × stop): o EA aplica sobre o
 * preço real da execução. O risco em US$ é distância × lote × valor do tick da corretora (MT5).</p>
 */
@Service
public class OrderService {

    public static final String SETTING_LOT = "orders.lot";
    public static final String SETTING_BLOCK = "orders.block";
    public static final String SETTING_FLATTEN = "orders.flatten";
    private static final int EA_MAX_SILENCE_SECONDS = 30;

    private final TradeRepository repo;
    private final PredictionRepository predictions;
    private final RiskSettingsHolder riskHolder;
    private final TradingProperties trading;
    private final InstrumentCatalog catalog;
    private final Mt5Repository mt5;
    private final ObjectProvider<JdbcClient> jdbc;
    private final ObjectMapper mapper;
    private final com.jevforex.app.config.ExperimentProperties experiment;

    public OrderService(TradeRepository repo, PredictionRepository predictions, RiskSettingsHolder riskHolder,
                        TradingProperties trading, InstrumentCatalog catalog, Mt5Repository mt5,
                        ObjectProvider<JdbcClient> jdbc, ObjectMapper mapper,
                        com.jevforex.app.config.ExperimentProperties experiment) {
        this.experiment = experiment;
        this.repo = repo;
        this.predictions = predictions;
        this.riskHolder = riskHolder;
        this.trading = trading;
        this.catalog = catalog;
        this.mt5 = mt5;
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    /** Uma trava: o que foi medido, o limite e se passou. */
    public record Check(String gate, boolean ok, String value, String limit) {
    }

    /**
     * O que aconteceria com a ordem, antes de enviar.
     *
     * @param riskUsd   perda se o stop for atingido (sem slippage: gap pode piorar)
     * @param rewardUsd ganho se o alvo for atingido
     * @param slPrice   stop aproximado (sobre a cotação de agora; o EA recalcula na execução)
     */
    public record Preview(String symbol, String side, double lot, Double refPrice, Double atr, Double slDistance,
                          Double tpDistance, Double slPrice, Double tpPrice, Double riskUsd, Double riskPct,
                          Double rewardUsd, Double balance, Double equity, Integer closeAfterMinutes, String mode,
                          Long predictionId, List<Check> checks, boolean ok) {
    }

    /** Lote do tíquete: o escolhido no dashboard ou o padrão de trading.risk.orders. */
    public double lot() {
        return repo.setting(SETTING_LOT).map(Double::parseDouble).orElse(riskHolder.get().orders().defaultLot());
    }

    /**
     * Limites de perda do dia e da semana: os de trading.risk.global, que vêm do perfil de risco aplicado na página
     * Risco (15/10/2026: a escolha à parte de um "perfil de perda" na página Operar saiu).
     */
    public String lossProfileName() {
        return RiskSettings.DEFAULT_PROFILE;
    }

    public RiskSettings.LossProfile lossProfile() {
        return riskHolder.get().lossProfile(RiskSettings.DEFAULT_PROFILE);
    }

    public boolean blocked() {
        return repo.setting(SETTING_BLOCK).map(Boolean::parseBoolean).orElse(false);
    }

    public boolean flattening() {
        return repo.setting(SETTING_FLATTEN).map(Boolean::parseBoolean).orElse(false);
    }

    /**
     * Ordem da Roleta (pedido do operador, 14/10/2026): parte das travas de RISCO ({@link #ROULETTE_WAIVED}) deixa de
     * bloquear e fica registrada como "ignorada na roleta". Continuam valendo as técnicas (EA conectado, modo × conta,
     * BLOCK/FLATTEN, cotação, ATR, lote, sessão), o disjuntor de perda do dia e da semana e, desde 19/10, posições,
     * par livre, mercado habilitado e risco aberto. A saída por tempo é o horizonte escolhido na roleta.
     *
     * @param predictionId a previsão que originou a ordem (auditoria); null no Cassino
     */
    public record Roulette(int closeAfterMinutes, Long predictionId, Double stopAtr, Double targetR, Double takeUsd,
                           Double stopUsd, boolean casino) {

        /**
         * @param closeAfterMinutes saída por tempo; 0 = sem (só SL/TP; no Cassino é opcional)
         * @param stopAtr / targetR saídas próprias (configuração do Laboratório); null = as do mercado
         * @param takeUsd / stopUsd alvo e stop em US$ com o lote da ordem (Cassino, 23/10/2026)
         * @param casino            Cassino: ignora TODAS as travas de risco por ordem ({@link #CASINO_WAIVED})
         */
        public Roulette {
        }

        public Roulette(int closeAfterMinutes, long predictionId) {
            this(closeAfterMinutes, predictionId, null, null, null, null, false);
        }

        public Roulette(int closeAfterMinutes, long predictionId, Double stopAtr, Double targetR) {
            this(closeAfterMinutes, predictionId, stopAtr, targetR, null, null, false);
        }

        /** Ordem do Cassino: alvo e stop em US$, saída por tempo opcional (0 = sem). */
        public static Roulette casino(int closeAfterMinutes, double takeUsd, double stopUsd) {
            return new Roulette(closeAfterMinutes, null, null, null, takeUsd, stopUsd, true);
        }
    }

    /**
     * Cassino (pedido do operador em 23/10/2026; antes, a alta frequência de 22/10): além das da roleta, ignora também
     * posições, par livre, mercado habilitado e risco aberto, e o ATR (stop e alvo são em US$, não em ATR). Valem só as
     * técnicas (EA, modo × conta, BLOCK/FLATTEN, cotação, lote, sessão, alvo e stop além de 2 spreads) e a perda do
     * dia/semana.
     */
    static final java.util.Set<String> CASINO_WAIVED = java.util.Set.of("Risco da ordem", "Risco-alvo do mercado",
            "Exposição líquida a USD", "Nível de margem", "Spread (gate 3)", "Fora de blackout (gate 2)",
            "Mercado habilitado", "Posições abertas", "Posições no mercado", "Par livre", "Risco aberto total",
            "Risco aberto no mercado", "Stop em US$ × spread", "ATR (última previsão)");

    /**
     * Travas que a Roleta registra mas não deixa bloquear. 14/10/2026: o operador escolheu manter só a perda do
     * dia/semana; 19/10/2026: passou a respeitar também posições abertas (conta e mercado), par livre, mercado
     * habilitado e risco aberto (conta e mercado) — com 7 posições abertas com limite 5.
     */
    static final java.util.Set<String> ROULETTE_WAIVED = java.util.Set.of("Risco da ordem", "Risco-alvo do mercado",
            "Exposição líquida a USD", "Nível de margem", "Spread (gate 3)", "Fora de blackout (gate 2)");

    public Preview preview(String symbol, String side, Double requestedLot) {
        return preview(symbol, side, requestedLot, null);
    }

    public Preview preview(String symbol, String side, Double requestedLot, Roulette roulette) {
        RiskSettings.Orders o = riskHolder.get().orders();
        List<Check> checks = new ArrayList<>();
        Instrument inst = instrument(symbol);
        if (inst == null) {
            checks.add(new Check("Símbolo", false, symbol, "instrumento com especificação do MT5"));
            return fail(symbol, side, requestedLot == null ? lot() : requestedLot, checks);
        }
        if (!"BUY".equals(side) && !"SELL".equals(side)) {
            checks.add(new Check("Lado", false, side, "BUY ou SELL"));
            return fail(symbol, side, lot(), checks);
        }
        double lot = requestedLot == null ? lot() : requestedLot;
        Instant now = Instant.now();

        // ------------------------------------------------------------ conta (heartbeat do EA)
        Optional<TradeRepository.Account> acc = repo.latestAccount();
        long silence = acc.map(a -> a.reportedAt() == null ? Long.MAX_VALUE
                : Duration.between(a.reportedAt(), now).toSeconds()).orElse(Long.MAX_VALUE);
        checks.add(new Check("EA conectado", silence <= EA_MAX_SILENCE_SECONDS,
                silence == Long.MAX_VALUE ? "sem heartbeat" : silence + " s desde o último heartbeat",
                "≤ " + EA_MAX_SILENCE_SECONDS + " s"));
        JsonNode detail = acc.map(a -> read(a.detailJson())).orElse(mapper.createObjectNode());
        Double balance = acc.map(TradeRepository.Account::balance).orElse(null);
        Double equity = acc.map(TradeRepository.Account::equity).orElse(null);
        String tradeMode = acc.map(TradeRepository.Account::tradeMode).orElse(null);
        TradingProperties.Mode mode = trading.mode();
        boolean modeOk = mode == TradingProperties.Mode.SHADOW
                || (mode == TradingProperties.Mode.DEMO && "DEMO".equalsIgnoreCase(tradeMode))
                || (mode == TradingProperties.Mode.LIVE && "REAL".equalsIgnoreCase(tradeMode));
        checks.add(new Check("Modo × conta", modeOk, "sistema " + mode + ", conta " + tradeMode,
                "SHADOW registra; DEMO exige conta demo; LIVE exige conta real"));
        checks.add(new Check("BLOCK / FLATTEN", !blocked() && !flattening(),
                blocked() ? "BLOCK ligado" : flattening() ? "FLATTEN em andamento" : "livre", "desligados"));
        RiskSettings.MarketRisk mr = marketRisk(inst.market());
        checks.add(new Check("Mercado habilitado", mr != null && mr.enabled(),
                mr == null ? "sem trading.risk.markets." + inst.market().code() : mr.enabled() ? "sim" : "desligado",
                "enabled na página Risco"));
        if (mr == null) return fail(symbol, side, lot, checks);
        int closeAfter = roulette != null ? roulette.closeAfterMinutes() : mr.closeAfter(o);

        // ------------------------------------------------------------ cotação e ATR
        JsonNode quote = null;
        for (JsonNode q : detail.path("quotes")) if (symbol.equals(q.path("symbol").asText())) quote = q;
        boolean quoteFresh = quote != null && silence <= o.quoteMaxAgeSeconds();
        checks.add(new Check("Cotação", quoteFresh, quote == null ? "sem cotação do EA" : silence + " s",
                "≤ " + o.quoteMaxAgeSeconds() + " s"));
        Double bid = quote == null ? null : quote.path("bid").asDouble();
        Double ask = quote == null ? null : quote.path("ask").asDouble();
        Integer spread = quote == null ? null : quote.path("spread").asInt();
        Double ref = quote == null ? null : "BUY".equals(side) ? ask : bid;
        checks.add(sessionCheck(quote, now, closeAfter, o));

        String market = inst.market().code();
        PredictionRepository.Prediction pred = predictions.latest(market).stream()
                .filter(p -> p.symbol().equals(symbol) && p.atr() != null)
                .max((a, b) -> a.momentUtc().compareTo(b.momentUtc())).orElse(null);
        long atrAge = pred == null ? Long.MAX_VALUE : Duration.between(pred.momentUtc(), now).toMinutes();
        checks.add(new Check("ATR (última previsão)", atrAge <= o.atrMaxAgeMinutes(),
                pred == null ? "sem previsão" : atrAge + " min", "≤ " + o.atrMaxAgeMinutes() + " min"));
        Double atr = pred == null ? null : pred.atr();

        // ------------------------------------------------------------ lote, stop e risco
        double minLot = inst.minLot(), maxLot = Math.min(o.maxLot(), inst.maxLot());
        boolean stepOk = Math.abs(lot / inst.lotStep() - Math.rint(lot / inst.lotStep())) < 1e-6;
        checks.add(new Check("Lote", lot >= minLot - 1e-9 && lot <= maxLot + 1e-9 && stepOk,
                fmt(lot, 2), String.format(Locale.ROOT, "%.2f a %.2f, passo %.2f", minLot, maxLot, inst.lotStep())));
        RiskSettings.Exits exits = roulette != null && roulette.stopAtr() != null && roulette.targetR() != null
                ? new RiskSettings.Exits(roulette.stopAtr(), roulette.targetR())   // roleta com a configuração do Laboratório
                : mr.exits(riskHolder.get().exits());   // as do mercado (ouro: as da regra validada)
        RiskSettings.MoneyExits money = riskHolder.get().money();
        Double slDist, tpDist;
        boolean hfTake = roulette != null && roulette.takeUsd() != null;
        if (hfTake) {
            // Cassino: alvo a US$ takeUsd e stop a US$ stopUsd com este lote (sem stopUsd: stopAtr × ATR)
            double perPrice = inst.lossPerLot(1.0) * lot;
            slDist = roulette.stopUsd() != null ? (perPrice > 0 ? roulette.stopUsd() / perPrice : null)
                    : atr == null || roulette.stopAtr() == null ? null : roulette.stopAtr() * atr;
            tpDist = perPrice > 0 ? roulette.takeUsd() / perPrice : null;
        } else if (money.enabled()) {
            // página "Stop em US$": a distância em preço que perde/ganha exatamente esses dólares com este lote
            double perPrice = inst.lossPerLot(1.0) * lot;
            slDist = perPrice > 0 ? money.stopUsd() / perPrice : null;
            tpDist = perPrice > 0 ? money.takeUsd() / perPrice : null;
        } else {
            slDist = atr == null ? null : exits.stopAtr() * atr;
            tpDist = slDist == null ? null : exits.targetR() * slDist;
        }
        Double riskUsd = slDist == null ? null : inst.lossPerLot(slDist) * lot;
        Double rewardUsd = tpDist == null ? null : inst.lossPerLot(tpDist) * lot;
        double cap = balance == null ? 0 : riskHolder.get().global().maxRiskPerTradeMoney(balance);
        checks.add(new Check("Risco da ordem", riskUsd != null && balance != null && riskUsd <= cap + 1e-9,
                riskUsd == null ? "sem stop" : usd(riskUsd) + pct(riskUsd, balance),
                "≤ " + usd(cap) + " (teto por trade)"));

        // risco-alvo do mercado e política do lote mínimo (19/10/2026: antes, risk-per-trade-pct e min-lot-policy
        // não eram conferidos nas ordens; só o teto valia)
        double target = balance == null ? 0 : balance * mr.riskPerTradePct() / 100;
        boolean atMinLot = Math.abs(lot - inst.minLot()) < 1e-9;
        boolean upToCap = atMinLot && riskHolder.get().global().minLotPolicy() == RiskSettings.MinLotPolicy.ALLOW_UP_TO_CAP;
        boolean targetOk = riskUsd != null && balance != null && (riskUsd <= target + 1e-9 || (upToCap && riskUsd <= cap + 1e-9));
        checks.add(new Check("Risco-alvo do mercado", targetOk,
                riskUsd == null ? "sem stop" : usd(riskUsd) + pct(riskUsd, balance)
                        + (riskUsd > target + 1e-9 && targetOk ? " · lote mínimo acima do alvo, dentro do teto" : ""),
                "≤ " + usd(target) + " (" + fmt(mr.riskPerTradePct(), 1) + "% do mercado)"
                        + (upToCap ? "; lote mínimo até o teto (ALLOW_UP_TO_CAP)" : atMinLot ? "; lote mínimo acima = não opera (SKIP)" : "")));

        // ------------------------------------------------------------ carteira
        // 19/10/2026: o limite de posições e o risco aberto DO MERCADO contavam a conta inteira (o menor dos dois limites
        // sobre todas as posições); agora a conta e cada mercado têm a própria trava
        List<TradeRepository.Position> positions = repo.positions();
        long pendingOpen = repo.openOrders("OPEN");
        long open = positions.size() + pendingOpen;
        int maxPos = riskHolder.get().global().maxPositions();
        checks.add(new Check("Posições abertas", open < maxPos, String.valueOf(open), "< " + maxPos + " na conta"));
        long openMarket = positions.stream().filter(p -> marketOf(p.symbol()).equals(market)).count()
                + repo.openOrders("OPEN", market);
        checks.add(new Check("Posições no mercado", openMarket < mr.maxPositions(), String.valueOf(openMarket),
                "< " + mr.maxPositions() + " em " + market));
        boolean samePair = positions.stream().anyMatch(p -> p.symbol().equals(symbol));
        checks.add(new Check("Par livre", !samePair, samePair ? "já há posição em " + symbol : "livre",
                "sem posição no mesmo par"));
        double openRisk = 0, openRiskMarket = 0;
        boolean allWithStop = true;
        String noStop = null;   // a posição sem stop (de fora do sistema, em geral): risco desconhecido = falhar fechado
        double usdNet = 0;
        for (TradeRepository.Position p : positions) {
            Instrument pi = instrument(p.symbol());
            if (p.sl() == null || p.sl() == 0 || pi == null) {
                allWithStop = false;
                noStop = p.symbol() + " #" + p.ticket();
                continue;
            }
            double r = pi.lossPerLot(Math.abs(p.openPrice() - p.sl())) * p.volume();
            openRisk += r;
            if (pi.market() == inst.market()) openRiskMarket += r;
            usdNet += usdSign(p.symbol(), p.side()) * r * usdWeight(pi.market());
        }
        double newRisk = riskUsd == null ? 0 : riskUsd;
        double openCap = balance == null ? 0 : balance * riskHolder.get().global().maxOpenRiskPct() / 100;
        checks.add(new Check("Risco aberto total", allWithStop && balance != null && openRisk + newRisk <= openCap + 1e-9,
                allWithStop ? usd(openRisk + newRisk) + pct(openRisk + newRisk, balance)
                        : "posição sem stop na conta (" + noStop + "): risco desconhecido",
                "≤ " + usd(openCap) + " na conta"));
        double marketCap = balance == null ? 0 : balance * mr.maxOpenRiskPct() / 100;
        checks.add(new Check("Risco aberto no mercado", balance != null && openRiskMarket + newRisk <= marketCap + 1e-9,
                usd(openRiskMarket + newRisk) + pct(openRiskMarket + newRisk, balance), "≤ " + usd(marketCap) + " em " + market));
        // exposição a USD: cada posição pelo peso do mercado dela (ouro, bitcoin, petróleo: comprar = vender dólar)
        int sign = usdSign(symbol, side);
        double usdAfter = Math.abs(usdNet + sign * newRisk * mr.usdWeight());
        double usdCap = balance == null ? 0 : balance * riskHolder.get().global().maxUsdNetExposurePct() / 100;
        checks.add(new Check("Exposição líquida a USD", balance != null && usdAfter <= usdCap + 1e-9,
                usd(usdAfter), "≤ " + usd(usdCap)));

        // ------------------------------------------------------------ perdas do dia e da semana, margem
        if (acc.isPresent() && equity != null) {
            long account = acc.get().account();
            Instant day = LocalDate.now(ZoneOffset.UTC).atStartOfDay().toInstant(ZoneOffset.UTC);
            Instant week = LocalDate.now(ZoneOffset.UTC).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                    .atStartOfDay().toInstant(ZoneOffset.UTC);
            double dayStart = repo.firstEquitySince(account, day).orElse(equity);
            double weekStart = repo.firstEquitySince(account, week).orElse(equity);
            double dayLoss = Math.max(0, (dayStart - equity) / dayStart * 100);
            double weekLoss = Math.max(0, (weekStart - equity) / weekStart * 100);
            RiskSettings.LossProfile lp = lossProfile();
            checks.add(new Check("Perda do dia", dayLoss < lp.maxDailyLossPct(), fmt(dayLoss, 1) + "%",
                    "< " + fmt(lp.maxDailyLossPct(), 1) + "% (perfil " + lp.label() + ")"));
            checks.add(new Check("Perda da semana", weekLoss < lp.maxWeeklyLossPct(), fmt(weekLoss, 1) + "%",
                    "< " + fmt(lp.maxWeeklyLossPct(), 1) + "% (perfil " + lp.label() + ")"));
        } else {
            checks.add(new Check("Perda do dia", false, "sem saldo do EA", "< " + lossProfile().maxDailyLossPct() + "%"));
        }
        double marginLevel = detail.path("margin_level").asDouble(0);
        boolean hasMargin = detail.path("margin").asDouble(0) > 0;
        checks.add(new Check("Nível de margem", !hasMargin || marginLevel >= o.minMarginLevelPct(),
                hasMargin ? fmt(marginLevel, 0) + "%" : "sem margem em uso", "≥ " + fmt(o.minMarginLevelPct(), 0) + "%"));

        if (money.enabled()) {
            // stop em dólares pequeno demais para o símbolo: dentro do spread ou do mínimo da corretora = sai na hora
            Mt5Parsers.InstrumentSpec sp = spec(symbol);
            double point = sp == null ? inst.tickSize() : sp.point();
            double minDist = Math.max(sp == null || sp.stopsLevel() == null ? 0 : sp.stopsLevel() * point,
                    spread == null ? 0 : 2.0 * spread * point);
            checks.add(new Check("Stop em US$ × spread", slDist != null && slDist > minDist,
                    slDist == null ? "sem stop" : String.format(Locale.ROOT, "%s de distância (US$ %.2f)",
                            fmt(slDist, 6), money.stopUsd()),
                    String.format(Locale.ROOT, "> %s (2 spreads ou o mínimo da corretora)", fmt(minDist, 6))));
        }

        if (hfTake) {
            // alvo em US$ perto demais: dentro do spread (o "lucro" seria só o spread) ou do mínimo da corretora (recusa)
            Mt5Parsers.InstrumentSpec sp = spec(symbol);
            double point = sp == null ? inst.tickSize() : sp.point();
            double minDist = Math.max(sp == null || sp.stopsLevel() == null ? 0 : sp.stopsLevel() * point,
                    spread == null ? 0 : 2.0 * spread * point);
            checks.add(new Check("Alvo em US$ × spread", tpDist != null && tpDist > minDist,
                    tpDist == null ? "sem alvo" : String.format(Locale.ROOT, "%s de distância (US$ %.2f)", fmt(tpDist, 6),
                            roulette.takeUsd()),
                    String.format(Locale.ROOT, "> %s (2 spreads ou o mínimo da corretora)", fmt(minDist, 6))));
            if (roulette.stopUsd() != null) {
                checks.add(new Check("Stop do Cassino × spread", slDist != null && slDist > minDist,
                        slDist == null ? "sem stop" : String.format(Locale.ROOT, "%s de distância (US$ %.2f)", fmt(slDist, 6),
                                roulette.stopUsd()),
                        String.format(Locale.ROOT, "> %s (2 spreads ou o mínimo da corretora)", fmt(minDist, 6))));
            }
        }

        // ------------------------------------------------------------ gates de mercado (cap. 12)
        Double typical = typicalSpread(pred, market);
        boolean spreadOk = spread != null && typical != null && spread <= o.maxSpreadRatio() * typical;
        checks.add(new Check("Spread (gate 3)", spreadOk,
                spread == null ? "sem cotação" : spread + " pts" + (typical == null ? "" : " (típico " + fmt(typical, 0) + ")"),
                typical == null ? "sem spread típico: rode Prever agora" : "≤ " + fmt(o.maxSpreadRatio() * typical, 0) + " pts"));
        List<String> events = blackoutEvents(currencies(symbol, inst), now, o);
        checks.add(new Check("Fora de blackout (gate 2)", events.isEmpty(),
                events.isEmpty() ? "nenhum evento alto perto" : String.join(", ", events),
                "sem evento alto de " + o.blackoutBeforeMinutes() + " min antes a " + o.blackoutAfterMinutes()
                        + " min depois"));

        if (roulette != null) {
            java.util.Set<String> waived = roulette.casino() ? CASINO_WAIVED : ROULETTE_WAIVED;
            String tag = roulette.casino() ? " · ignorada no Cassino" : " · ignorada na roleta";
            checks.replaceAll(c -> c.ok() || !waived.contains(c.gate()) ? c : new Check(c.gate(), true, c.value() + tag, c.limit()));
        }
        boolean ok = checks.stream().allMatch(Check::ok);
        Double slPrice = ref == null || slDist == null ? null : "BUY".equals(side) ? ref - slDist : ref + slDist;
        Double tpPrice = ref == null || tpDist == null ? null : "BUY".equals(side) ? ref + tpDist : ref - tpDist;
        return new Preview(symbol, side, lot, ref, atr, slDist, tpDist, slPrice, tpPrice, riskUsd,
                riskUsd == null || balance == null ? null : riskUsd / balance * 100, rewardUsd, balance, equity,
                closeAfter == 0 ? null : closeAfter, mode.name(),
                roulette != null ? roulette.predictionId() : pred == null ? null : pred.id(), checks, ok);
    }

    /** O resultado de um envio: a ordem gravada ou as travas que impediram. */
    public record Submit(Long orderId, String status, Preview preview) {
    }

    /** Confere de novo AGORA (o tíquete pode ter ficado aberto) e grava a ordem para o EA buscar. */
    public synchronized Submit submit(String symbol, String side, Double lot) {
        return submit(symbol, side, lot, null);
    }

    public synchronized Submit submit(String symbol, String side, Double lot, Roulette roulette) {
        Preview p = preview(symbol, side, lot, roulette);
        if (!p.ok()) return new Submit(null, "RECUSADA", p);
        boolean shadow = trading.mode() == TradingProperties.Mode.SHADOW;
        Instant now = Instant.now();
        long id = repo.insertOrder(new TradeRepository.Order(null, marketOf(symbol), symbol, "OPEN", side, p.lot(), p.slDistance(),
                p.tpDistance(), p.refPrice(), p.atr(), p.riskUsd(), riskHolder.get().orders().deviationPoints(),
                p.closeAfterMinutes() == null ? null : now.plus(Duration.ofMinutes(p.closeAfterMinutes())), null,
                p.predictionId(), json(p.checks()), shadow ? "SHADOW" : "PENDING",
                shadow ? "modo SHADOW: registrada, não enviada ao MT5" : null, null, null,
                now, now.plusSeconds(riskHolder.get().orders().validitySeconds()), null, null));
        return new Submit(id, shadow ? "SHADOW" : "PENDING", p);
    }

    /**
     * Prévia da página "Stop em US$": com estes dólares e este lote (ou o mínimo do símbolo, se for maior), a que
     * distância ficam o stop e o alvo em cada símbolo e se o stop fica longe o bastante do spread.
     */
    public record MoneyPreview(String market, String symbol, double lot, Double stopDistance, Double takeDistance,
                               Double stopPoints, Integer spreadPoints, Integer stopsLevel, boolean ok) {
    }

    public List<MoneyPreview> moneyPreview(double lot, double stopUsd, double takeUsd) {
        JsonNode detail = repo.latestAccount().map(a -> read(a.detailJson())).orElse(mapper.createObjectNode());
        java.util.Map<String, Integer> spreads = new java.util.HashMap<>();
        for (JsonNode q : detail.path("quotes")) {
            if (q.hasNonNull("spread")) spreads.put(q.path("symbol").asText(), q.path("spread").asInt());
        }
        List<MoneyPreview> out = new ArrayList<>();
        symbolsByMarket().forEach((market, symbols) -> {
            for (String symbol : symbols) {
                Instrument inst = instrument(symbol);
                if (inst == null) continue;
                Mt5Parsers.InstrumentSpec sp = spec(symbol);
                double l = Math.max(lot, inst.minLot());
                double perPrice = inst.lossPerLot(1.0) * l;
                double point = sp == null ? inst.tickSize() : sp.point();
                Integer spread = spreads.getOrDefault(symbol, sp == null ? null : sp.spreadPoints());
                Integer stops = sp == null ? null : sp.stopsLevel();
                Double sd = perPrice > 0 ? stopUsd / perPrice : null, td = perPrice > 0 ? takeUsd / perPrice : null;
                double min = Math.max(stops == null ? 0 : stops * point, spread == null ? 0 : 2.0 * spread * point);
                out.add(new MoneyPreview(market, symbol, l, sd, td, sd == null ? null : sd / point, spread, stops,
                        sd != null && sd > min));
            }
        });
        return out;
    }

    /**
     * Move o stop de uma posição aberta para um preço (trailing). O alvo fica onde está. Como fechar, proteger nunca é
     * bloqueado por BLOCK.
     */
    public long modifyStop(TradeRepository.Position pos, double slPrice, String why) {
        return modify(pos, slPrice, null, why);
    }

    /**
     * Move o stop e, se {@code tpPrice} não for null, também o alvo (prorrogação com lucro; EA 2.4). Proteger nunca é
     * bloqueado por BLOCK.
     */
    public synchronized long modify(TradeRepository.Position pos, double slPrice, Double tpPrice, String why) {
        return modify(pos, slPrice, tpPrice, why, null);
    }

    private long modify(TradeRepository.Position pos, double slPrice, Double tpPrice, String why, String mode) {
        Instant now = Instant.now();
        return repo.insertOrder(new TradeRepository.Order(null, marketOf(pos.symbol()), pos.symbol(), "MODIFY", pos.side(),
                pos.volume(), null, null, null, null, null, riskHolder.get().orders().deviationPoints(), null,
                pos.ticket(), null, "[]", trading.mode() == TradingProperties.Mode.SHADOW ? "SHADOW" : "PENDING", why,
                null, pos.account(), now, now.plusSeconds(riskHolder.get().orders().validitySeconds()), null, null,
                slPrice, tpPrice, mode));
    }

    /** Resultado da edição de stop/alvo pelo dashboard: a ordem MODIFY gravada ou (orderId null) o motivo da recusa. */
    public record StopEdit(Long orderId, String message, Double slUsd, Double tpUsd) {
    }

    /**
     * Edição manual de stop e alvo de uma posição aberta (página Operar, pedido do operador em 20/10/2026). O stop pode
     * se afastar, mas a perda nele não passa do teto por operação (página Risco); stop e alvo do lado certo e além da
     * distância mínima da corretora; o stop nunca é removido (falhar fechado). {@code tp} null = mantém o alvo atual.
     * Precisa do EA 2.6 (MODIFY MANUAL). Como fechar, proteger não é bloqueado por BLOCK.
     */
    public synchronized StopEdit editStops(long ticket, Double sl, Double tp) {
        TradeRepository.Position pos = repo.ownPositions().stream().filter(p -> p.ticket() == ticket).findFirst().orElse(null);
        if (pos == null) {
            return refuse(repo.positions().stream().anyMatch(p -> p.ticket() == ticket)
                    ? "A posição " + ticket + " foi aberta fora do sistema: edite no MT5." : "A posição " + ticket + " não está aberta nesta conta.");
        }
        if (sl == null || !(sl > 0)) return refuse("O stop é obrigatório: toda posição fica com stop no servidor da corretora.");
        Optional<TradeRepository.Account> acc = repo.latestAccount();
        long silence = acc.map(a -> a.reportedAt() == null ? Long.MAX_VALUE
                : Duration.between(a.reportedAt(), Instant.now()).toSeconds()).orElse(Long.MAX_VALUE);
        if (silence > EA_MAX_SILENCE_SECONDS) return refuse("O EA não mandou heartbeat nos últimos " + EA_MAX_SILENCE_SECONDS + " s.");
        JsonNode detail = read(acc.get().detailJson());
        if (!eaAtLeast(detail.path("ea_version").asText(""), 2, 6)) {
            return refuse("O EA conectado (" + detail.path("ea_version").asText("versão desconhecida") + ") não edita stop e alvo: "
                    + "precisa da 2.6 (rode mql5\\install.ps1 e recarregue o JevExecutor).");
        }
        JsonNode quote = null;
        for (JsonNode q : detail.path("quotes")) if (pos.symbol().equals(q.path("symbol").asText())) quote = q;
        if (quote == null) return refuse("Sem cotação de " + pos.symbol() + " no heartbeat do EA.");
        double bid = quote.path("bid").asDouble(), ask = quote.path("ask").asDouble();
        Instrument inst = instrument(pos.symbol());
        if (inst == null) return refuse("Sem especificação de " + pos.symbol() + ".");
        Mt5Parsers.InstrumentSpec sp = spec(pos.symbol());
        double point = sp == null ? inst.tickSize() : sp.point();
        double minDist = (sp == null || sp.stopsLevel() == null ? 0 : sp.stopsLevel()) * point + point;
        boolean buy = "BUY".equals(pos.side());
        Double target = tp != null ? tp : pos.tp() != null && pos.tp() > 0 ? pos.tp() : null;
        if (target == null || !(target > 0)) return refuse("Informe o alvo (a posição está sem alvo).");
        double slP = onTick(sl, inst.tickSize()), tpP = onTick(target, inst.tickSize());
        if (buy && slP > bid - minDist) return refuse(String.format(Locale.ROOT, "Compra: o stop precisa ficar abaixo de %s (bid %s menos a distância mínima da corretora).", fmt(bid - minDist, 6), fmt(bid, 6)));
        if (!buy && slP < ask + minDist) return refuse(String.format(Locale.ROOT, "Venda: o stop precisa ficar acima de %s (ask %s mais a distância mínima da corretora).", fmt(ask + minDist, 6), fmt(ask, 6)));
        if (buy && tpP < bid + minDist) return refuse(String.format(Locale.ROOT, "Compra: o alvo precisa ficar acima de %s.", fmt(bid + minDist, 6)));
        if (!buy && tpP > ask - minDist) return refuse(String.format(Locale.ROOT, "Venda: o alvo precisa ficar abaixo de %s.", fmt(ask - minDist, 6)));
        double perPrice = inst.lossPerLot(1.0) * pos.volume();
        double slUsd = (buy ? slP - pos.openPrice() : pos.openPrice() - slP) * perPrice;   // negativo = perda no stop
        double tpUsd = (buy ? tpP - pos.openPrice() : pos.openPrice() - tpP) * perPrice;
        Double balance = acc.get().balance();
        double cap = balance == null ? 0 : riskHolder.get().global().maxRiskPerTradeMoney(balance);
        if (-slUsd > cap + 1e-9) {
            return refuse(String.format(Locale.ROOT, "A perda no novo stop (US$ %.2f) passa do teto por operação (US$ %.2f, página Risco).", -slUsd, cap));
        }
        String why = String.format(Locale.ROOT, "editado no dashboard: stop %s (%s US$ %.2f), alvo %s (+US$ %.2f)",
                fmt(slP, 6), slUsd >= 0 ? "garante" : "perde", Math.abs(slUsd), fmt(tpP, 6), tpUsd);
        long id = modify(pos, slP, tpP, why, "MANUAL");
        return new StopEdit(id, why, slUsd, tpUsd);
    }

    private static StopEdit refuse(String why) {
        return new StopEdit(null, why, null, null);
    }

    /** O heartbeat traz "JevExecutor/2.6": versão ≥ major.minor. */
    static boolean eaAtLeast(String version, int wantMajor, int wantMinor) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("/(\\d+)\\.(\\d+)").matcher(version == null ? "" : version);
        if (!m.find()) return false;
        int major = Integer.parseInt(m.group(1)), minor = Integer.parseInt(m.group(2));
        return major > wantMajor || (major == wantMajor && minor >= wantMinor);
    }

    private static double onTick(double price, double tick) {
        if (!(tick > 0)) return price;
        return java.math.BigDecimal.valueOf(Math.round(price / tick)).multiply(java.math.BigDecimal.valueOf(tick)).doubleValue();
    }

    /** Fecha uma posição (botão no dashboard ou saída por tempo). Fechar nunca é bloqueado por BLOCK. */
    public synchronized long close(long ticket, String why) {
        TradeRepository.Position pos = repo.ownPositions().stream().filter(p -> p.ticket() == ticket).findFirst()
                .orElseThrow(() -> new IllegalArgumentException(repo.positions().stream().anyMatch(p -> p.ticket() == ticket)
                        ? "Posição " + ticket + " foi aberta fora do sistema: feche no MT5"
                        : "Posição " + ticket + " não está aberta"));
        Instant now = Instant.now();
        return repo.insertOrder(new TradeRepository.Order(null, marketOf(pos.symbol()), pos.symbol(), "CLOSE", null, pos.volume(), null,
                null, null, null, null, riskHolder.get().orders().deviationPoints(), null, ticket, null, "[]",
                trading.mode() == TradingProperties.Mode.SHADOW ? "SHADOW" : "PENDING", why, null, pos.account(),
                now, now.plusSeconds(riskHolder.get().orders().validitySeconds()), null, null));
    }

    /** FLATTEN: cancela o que está pendente, liga o BLOCK e manda o EA fechar tudo deste sistema. */
    public synchronized void flatten() {
        repo.cancelPending("FLATTEN");
        repo.setSetting(SETTING_BLOCK, "true");
        repo.setSetting(SETTING_FLATTEN, "true");
    }

    public void block(boolean on) {
        repo.setSetting(SETTING_BLOCK, String.valueOf(on));
        if (on) repo.cancelPending("BLOCK");
    }

    public double setLot(double lot) {
        if (!(lot > 0) || lot > riskHolder.get().orders().maxLot() + 1e-9) {
            throw new IllegalArgumentException(String.format(Locale.ROOT,
                    "Lote %.2f fora do permitido (até %.2f, trading.risk.orders.max-lot)", lot, riskHolder.get().orders().maxLot()));
        }
        repo.setSetting(SETTING_LOT, String.format(Locale.ROOT, "%.2f", lot));
        return lot;
    }

    /**
     * A posição precisa poder fechar por tempo DENTRO da sessão do símbolo (16/10/2026: aberta às 20:02 de sexta com
     * saída em 60 min, ficou para o fim de semana e a corretora recusou cada fechamento). O EA 2.5 manda, em cada
     * cotação, quando a sessão de negociação atual termina ({@code session_end}, UTC; vazio = mercado fechado agora).
     * EA anterior (sem o campo): passa, avisando. Sem saída por tempo: só SL/TP, passa.
     */
    static Check sessionCheck(JsonNode quote, Instant now, int closeAfterMinutes, RiskSettings.Orders o) {
        String gate = "Sessão até a saída por tempo";
        if (closeAfterMinutes <= 0) return new Check(gate, true, "sem saída por tempo (só SL/TP)", "—");
        if (quote == null || !quote.has("session_end")) {
            return new Check(gate, true, "sem horário da sessão (EA anterior à 2.5)", "não conferido");
        }
        String end = quote.path("session_end").asText("");
        Instant exit = now.plus(Duration.ofMinutes(closeAfterMinutes));
        String limit = "saída por tempo ≥ " + o.sessionMarginMinutes() + " min antes do fim da sessão";
        if (end.isBlank()) return new Check(gate, false, "mercado fechado agora", limit);
        Instant sessionEnd;
        try {
            sessionEnd = Instant.parse(end);
        } catch (RuntimeException e) {
            return new Check(gate, false, "horário da sessão ilegível: " + end, limit);
        }
        boolean ok = !exit.plus(Duration.ofMinutes(o.sessionMarginMinutes())).isAfter(sessionEnd);
        java.time.format.DateTimeFormatter f = java.time.format.DateTimeFormatter.ofPattern("EEE HH:mm", new Locale("pt", "BR"))
                .withZone(ZoneOffset.UTC);
        return new Check(gate, ok, "sai " + f.format(exit) + " UTC · sessão até " + f.format(sessionEnd) + " UTC", limit);
    }

    // ------------------------------------------------------------------ apoio

    /** Especificação real da corretora (MT5) se houver; senão a aproximada do application.yml. */
    public Instrument instrument(String symbol) {
        Instrument base = catalog.bySymbol().get(symbol);
        Mt5Parsers.InstrumentSpec s = spec(symbol);
        if (s != null) {
            double tv = s.tickValueLoss() != null && s.tickValueLoss() > 0 ? s.tickValueLoss() : s.tickValue();
            tv *= AccountCurrency.toUsd(s.accountCurrency());   // conta cent: o MT5 dá o valor do tick em USC
            return new Instrument(symbol, Market.fromCode(s.market()), s.tickSize(), tv, s.volumeMin(),
                    s.volumeStep(), s.volumeMax(), base == null ? 0 : base.typicalStop());
        }
        return base;   // índices e ações só existem com a especificação do MT5
    }

    /** Tamanho do ponto (spread e distâncias do MT5 vêm em pontos); sem especificação, o tick do instrumento. */
    public Double point(String symbol) {
        Mt5Parsers.InstrumentSpec sp = spec(symbol);
        if (sp != null) return sp.point();
        Instrument i = instrument(symbol);
        return i == null ? null : i.tickSize();
    }

    Mt5Parsers.InstrumentSpec spec(String symbol) {
        for (Mt5Parsers.InstrumentSpec s : mt5.latestSpecs()) if (s.symbol().equals(symbol)) return s;
        return null;
    }

    /** As moedas cujo calendário mexe no símbolo (página Universo); vazio sem especificação. */
    public List<String> currenciesOf(String symbol) {
        Instrument inst = instrument(symbol);
        return inst == null ? List.of() : currencies(symbol, inst);
    }

    /** Moedas cujo calendário mexe no instrumento: as duas do par; num índice/ação, a de lucro na corretora. */
    private List<String> currencies(String symbol, Instrument inst) {
        if (inst.market() == Market.FX && symbol.length() == 6) return List.of(symbol.substring(0, 3), symbol.substring(3));
        Mt5Parsers.InstrumentSpec s = spec(symbol);
        return s == null || s.currencyProfit() == null ? List.of() : List.of(s.currencyProfit());
    }

    /** Spread típico do horário (pontos) = spread da previsão ÷ spread_rel (a mediana dos 20 dias, cap. 10). */
    private Double typicalSpread(PredictionRepository.Prediction pred, String market) {
        if (pred == null || pred.spreadPoints() == null) return null;
        // spread_rel só existe nos modelos com o grupo de preço: procura uma previsão do mesmo momento que tenha
        for (PredictionRepository.Prediction p : predictions.latest(market)) {
            if (!p.symbol().equals(pred.symbol()) || !p.momentUtc().equals(pred.momentUtc())) continue;
            double rel = read(p.featuresJson()).path("spread_rel").asDouble(0);
            if (rel > 0) return p.spreadPoints() / rel;
        }
        return null;
    }

    private List<String> blackoutEvents(List<String> currencies, Instant now, RiskSettings.Orders o) {
        if (currencies.isEmpty()) return List.of();
        return jdbc.getObject().sql("""
                        SELECT DISTINCT v.currency || ' ' || to_char(v.scheduled_at AT TIME ZONE 'UTC', 'HH24:MI') || ' '
                               || v.event_code
                          FROM calendar_event v
                         WHERE v.importance = 'HIGH' AND v.currency IN (:cur)
                           AND v.scheduled_at BETWEEN :from AND :to
                        """)
                .param("cur", currencies)
                .param("from", Timestamp.from(now.minus(Duration.ofMinutes(o.blackoutAfterMinutes()))))
                .param("to", Timestamp.from(now.plus(Duration.ofMinutes(o.blackoutBeforeMinutes()))))
                .query(String.class).list();
    }

    private RiskSettings.MarketRisk marketRisk(Market market) {
        try {
            return riskHolder.get().forMarket(market);
        } catch (IllegalArgumentException e) {
            return null;   // sem orçamento para o mercado = sem ordem
        }
    }

    /** Mercado do símbolo (especificação do MT5; senão o do application.yml; senão fx). */
    public String marketOf(String symbol) {
        Mt5Parsers.InstrumentSpec s = spec(symbol);
        if (s != null) return s.market();
        Instrument i = catalog.bySymbol().get(symbol);
        return i == null ? "fx" : i.market().code();
    }

    /** Símbolos operáveis por mercado: forex do application.yml; os demais das especificações do MT5. */
    public java.util.Map<String, List<String>> symbolsByMarket() {
        java.util.Map<String, java.util.TreeSet<String>> m = new java.util.TreeMap<>();
        catalog.bySymbol().values().forEach(i -> m.computeIfAbsent(i.market().code(), k -> new java.util.TreeSet<>())
                .add(i.symbol()));
        for (Mt5Parsers.InstrumentSpec s : mt5.latestSpecs()) {
            if (!"fx".equals(s.market())) {   // índices, ações, ouro e bitcoin vêm das especificações do MT5
                List<String> allowed = experiment.symbolsOf(s.market());
                if (allowed == null || allowed.contains(s.symbol())) {
                    m.computeIfAbsent(s.market(), k -> new java.util.TreeSet<>()).add(s.symbol());
                }
            }
        }
        java.util.Map<String, List<String>> out = new java.util.LinkedHashMap<>();
        m.forEach((k, v) -> out.put(k, List.copyOf(v)));
        return out;
    }

    /** +1 se a posição ganha com o USD subindo, −1 se perde, 0 se o par não tem USD. */
    /** Peso do mercado na exposição líquida a USD (trading.risk.markets.<m>.usd-weight; sem configuração: forex 1, resto 0). */
    private double usdWeight(Market m) {
        RiskSettings.MarketRisk r = riskHolder.get().markets().get(m);
        return r == null ? (m == Market.FX ? 1.0 : 0.0) : r.usdWeight();
    }

    static int usdSign(String symbol, String side) {
        int s = "BUY".equals(side) ? 1 : -1;
        if (symbol.startsWith("USD")) return s;
        if (symbol.endsWith("USD")) return -s;
        return 0;
    }

    private Preview fail(String symbol, String side, double lot, List<Check> checks) {
        return new Preview(symbol, side, lot, null, null, null, null, null, null, null, null, null, null, null, null,
                trading.mode().name(), null, checks, false);
    }

    private JsonNode read(String json) {
        try {
            return json == null ? mapper.createObjectNode() : mapper.readTree(json);
        } catch (Exception e) {
            return mapper.createObjectNode();
        }
    }

    private String json(Object o) {
        try {
            return mapper.writeValueAsString(o);
        } catch (Exception e) {
            return "[]";
        }
    }

    private static String fmt(double v, int d) {
        return String.format(Locale.ROOT, "%." + d + "f", v);
    }

    private static String usd(double v) {
        return String.format(Locale.ROOT, "US$ %.2f", v);
    }

    private static String pct(double v, Double balance) {
        return balance == null || balance <= 0 ? "" : String.format(Locale.ROOT, " (%.1f%% do saldo)", v / balance * 100);
    }
}
