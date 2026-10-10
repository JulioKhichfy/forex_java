package com.jevforex.app.trade;

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
    public static final String SETTING_LOSS_PROFILE = "risk.loss-profile";
    private static final int EA_MAX_SILENCE_SECONDS = 30;

    private final TradeRepository repo;
    private final PredictionRepository predictions;
    private final RiskSettings risk;
    private final TradingProperties trading;
    private final InstrumentCatalog catalog;
    private final Mt5Repository mt5;
    private final ObjectProvider<JdbcClient> jdbc;
    private final ObjectMapper mapper;

    public OrderService(TradeRepository repo, PredictionRepository predictions, RiskSettings risk,
                        TradingProperties trading, InstrumentCatalog catalog, Mt5Repository mt5,
                        ObjectProvider<JdbcClient> jdbc, ObjectMapper mapper) {
        this.repo = repo;
        this.predictions = predictions;
        this.risk = risk;
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
        return repo.setting(SETTING_LOT).map(Double::parseDouble).orElse(risk.orders().defaultLot());
    }

    /** Nome do perfil de perda em vigor (escolhido no dashboard; padrão = limites de trading.risk.global). */
    public String lossProfileName() {
        return repo.setting(SETTING_LOSS_PROFILE).filter(risk.lossProfiles()::containsKey)
                .orElse(RiskSettings.DEFAULT_PROFILE);
    }

    public RiskSettings.LossProfile lossProfile() {
        return risk.lossProfile(lossProfileName());
    }

    public RiskSettings.LossProfile setLossProfile(String name) {
        RiskSettings.LossProfile p = risk.lossProfile(name);   // nome desconhecido = erro
        repo.setSetting(SETTING_LOSS_PROFILE, name);
        return p;
    }

    public boolean blocked() {
        return repo.setting(SETTING_BLOCK).map(Boolean::parseBoolean).orElse(false);
    }

    public boolean flattening() {
        return repo.setting(SETTING_FLATTEN).map(Boolean::parseBoolean).orElse(false);
    }

    public Preview preview(String symbol, String side, Double requestedLot) {
        RiskSettings.Orders o = risk.orders();
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
        Double slDist = atr == null ? null : risk.exits().stopAtr() * atr;
        Double tpDist = slDist == null ? null : risk.exits().targetR() * slDist;
        Double riskUsd = slDist == null ? null : inst.lossPerLot(slDist) * lot;
        Double rewardUsd = tpDist == null ? null : inst.lossPerLot(tpDist) * lot;
        double cap = balance == null ? 0 : risk.global().maxRiskPerTradeMoney(balance);
        checks.add(new Check("Risco da ordem", riskUsd != null && balance != null && riskUsd <= cap + 1e-9,
                riskUsd == null ? "sem stop" : usd(riskUsd) + pct(riskUsd, balance),
                "≤ " + usd(cap) + " (teto por trade)"));

        // ------------------------------------------------------------ carteira
        List<TradeRepository.Position> positions = repo.positions();
        long open = positions.size() + repo.openOrders("OPEN");
        RiskSettings.MarketRisk mr = risk.forMarket(inst.market());
        int maxPos = Math.min(risk.global().maxPositions(), mr.maxPositions());
        checks.add(new Check("Posições abertas", open < maxPos, String.valueOf(open), "< " + maxPos));
        boolean samePair = positions.stream().anyMatch(p -> p.symbol().equals(symbol));
        checks.add(new Check("Par livre", !samePair, samePair ? "já há posição em " + symbol : "livre",
                "sem posição no mesmo par"));
        double openRisk = 0;
        boolean allWithStop = true;
        double usdNet = 0;
        for (TradeRepository.Position p : positions) {
            Instrument pi = instrument(p.symbol());
            if (p.sl() == null || p.sl() == 0 || pi == null) {
                allWithStop = false;
                continue;
            }
            double r = pi.lossPerLot(Math.abs(p.openPrice() - p.sl())) * p.volume();
            openRisk += r;
            if (pi.market() == Market.FX) usdNet += usdSign(p.symbol(), p.side()) * r;
        }
        double newOpen = openRisk + (riskUsd == null ? 0 : riskUsd);
        double openCap = balance == null ? 0 : balance * Math.min(risk.global().maxOpenRiskPct(), mr.maxOpenRiskPct()) / 100;
        checks.add(new Check("Risco aberto total", allWithStop && balance != null && newOpen <= openCap + 1e-9,
                allWithStop ? usd(newOpen) + pct(newOpen, balance) : "posição sem stop na conta", "≤ " + usd(openCap)));
        int sign = inst.market() == Market.FX ? usdSign(symbol, side) : 0;   // exposição a USD: só no forex
        double usdAfter = Math.abs(usdNet + sign * (riskUsd == null ? 0 : riskUsd) * mr.usdWeight());
        double usdCap = balance == null ? 0 : balance * risk.global().maxUsdNetExposurePct() / 100;
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

        boolean ok = checks.stream().allMatch(Check::ok);
        Double slPrice = ref == null || slDist == null ? null : "BUY".equals(side) ? ref - slDist : ref + slDist;
        Double tpPrice = ref == null || tpDist == null ? null : "BUY".equals(side) ? ref + tpDist : ref - tpDist;
        return new Preview(symbol, side, lot, ref, atr, slDist, tpDist, slPrice, tpPrice, riskUsd,
                riskUsd == null || balance == null ? null : riskUsd / balance * 100, rewardUsd, balance, equity,
                o.closeAfterMinutes() == 0 ? null : o.closeAfterMinutes(), mode.name(),
                pred == null ? null : pred.id(), checks, ok);
    }

    /** O resultado de um envio: a ordem gravada ou as travas que impediram. */
    public record Submit(Long orderId, String status, Preview preview) {
    }

    /** Confere de novo AGORA (o tíquete pode ter ficado aberto) e grava a ordem para o EA buscar. */
    public synchronized Submit submit(String symbol, String side, Double lot) {
        Preview p = preview(symbol, side, lot);
        if (!p.ok()) return new Submit(null, "RECUSADA", p);
        boolean shadow = trading.mode() == TradingProperties.Mode.SHADOW;
        Instant now = Instant.now();
        long id = repo.insertOrder(new TradeRepository.Order(null, marketOf(symbol), symbol, "OPEN", side, p.lot(), p.slDistance(),
                p.tpDistance(), p.refPrice(), p.atr(), p.riskUsd(), risk.orders().deviationPoints(),
                p.closeAfterMinutes() == null ? null : now.plus(Duration.ofMinutes(p.closeAfterMinutes())), null,
                p.predictionId(), json(p.checks()), shadow ? "SHADOW" : "PENDING",
                shadow ? "modo SHADOW: registrada, não enviada ao MT5" : null, null, null,
                now, now.plusSeconds(risk.orders().validitySeconds()), null, null));
        return new Submit(id, shadow ? "SHADOW" : "PENDING", p);
    }

    /** Fecha uma posição (botão no dashboard ou saída por tempo). Fechar nunca é bloqueado por BLOCK. */
    public synchronized long close(long ticket, String why) {
        TradeRepository.Position pos = repo.positions().stream().filter(p -> p.ticket() == ticket).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Posição " + ticket + " não está aberta"));
        Instant now = Instant.now();
        return repo.insertOrder(new TradeRepository.Order(null, marketOf(pos.symbol()), pos.symbol(), "CLOSE", null, pos.volume(), null,
                null, null, null, null, risk.orders().deviationPoints(), null, ticket, null, "[]",
                trading.mode() == TradingProperties.Mode.SHADOW ? "SHADOW" : "PENDING", why, null, pos.account(),
                now, now.plusSeconds(risk.orders().validitySeconds()), null, null));
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
        if (!(lot > 0) || lot > risk.orders().maxLot() + 1e-9) {
            throw new IllegalArgumentException(String.format(Locale.ROOT,
                    "Lote %.2f fora do permitido (até %.2f, trading.risk.orders.max-lot)", lot, risk.orders().maxLot()));
        }
        repo.setSetting(SETTING_LOT, String.format(Locale.ROOT, "%.2f", lot));
        return lot;
    }

    // ------------------------------------------------------------------ apoio

    /** Especificação real da corretora (MT5) se houver; senão a aproximada do application.yml. */
    Instrument instrument(String symbol) {
        Instrument base = catalog.bySymbol().get(symbol);
        Mt5Parsers.InstrumentSpec s = spec(symbol);
        if (s != null) {
            double tv = s.tickValueLoss() != null && s.tickValueLoss() > 0 ? s.tickValueLoss() : s.tickValue();
            return new Instrument(symbol, Market.fromCode(s.market()), s.tickSize(), tv, s.volumeMin(),
                    s.volumeStep(), s.volumeMax(), base == null ? 0 : base.typicalStop());
        }
        return base;   // índices e ações só existem com a especificação do MT5
    }

    private Mt5Parsers.InstrumentSpec spec(String symbol) {
        for (Mt5Parsers.InstrumentSpec s : mt5.latestSpecs()) if (s.symbol().equals(symbol)) return s;
        return null;
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

    /** Mercado do símbolo (especificação do MT5; senão o do application.yml; senão fx). */
    public String marketOf(String symbol) {
        Mt5Parsers.InstrumentSpec s = spec(symbol);
        if (s != null) return s.market();
        Instrument i = catalog.bySymbol().get(symbol);
        return i == null ? "fx" : i.market().code();
    }

    /** Símbolos operáveis por mercado: forex do application.yml; índices e ações das especificações do MT5. */
    public java.util.Map<String, List<String>> symbolsByMarket() {
        java.util.Map<String, java.util.TreeSet<String>> m = new java.util.TreeMap<>();
        catalog.bySymbol().values().forEach(i -> m.computeIfAbsent(i.market().code(), k -> new java.util.TreeSet<>())
                .add(i.symbol()));
        for (Mt5Parsers.InstrumentSpec s : mt5.latestSpecs()) {
            if ("indices".equals(s.market()) || "stocks".equals(s.market())) {
                m.computeIfAbsent(s.market(), k -> new java.util.TreeSet<>()).add(s.symbol());
            }
        }
        java.util.Map<String, List<String>> out = new java.util.LinkedHashMap<>();
        m.forEach((k, v) -> out.put(k, List.copyOf(v)));
        return out;
    }

    /** +1 se a posição ganha com o USD subindo, −1 se perde, 0 se o par não tem USD. */
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
