package com.jevforex.app.trade;

import com.jevforex.app.config.RiskSettingsHolder;
import com.jevforex.app.live.PredictionRepository;
import com.jevforex.core.Instrument;
import com.jevforex.core.Market;
import com.jevforex.core.risk.RiskSettings;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Backtest das previsões já resolvidas (página Previsões): e se cada previsão do período tivesse virado uma ordem no
 * lado mais provável? Acertos, derrotas e o resultado em R e em US$.
 *
 * <p>O R de cada previsão é o do placar ({@code r_buy}/{@code r_sell}: stop, alvo e saída no horizonte, com spread,
 * com as saídas da página Risco de quando a previsão foi resolvida). O US$ converte esse R pela perda no stop de hoje
 * com o lote escolhido (ou o stop em US$, se estiver ligado). Previsões manuais ficam fora, como no placar.</p>
 */
@Service
@Profile("!cli")
public class PredictionBacktest {

    private final PredictionRepository predictions;
    private final OrderService orders;
    private final RiskSettingsHolder riskHolder;

    public PredictionBacktest(PredictionRepository predictions, OrderService orders, RiskSettingsHolder riskHolder) {
        this.predictions = predictions;
        this.orders = orders;
        this.riskHolder = riskHolder;
    }

    /**
     * @param symbol   um par ou null = todos do mercado
     * @param period   1h | today | 24h | 48h | 7d | 30d
     * @param side     all | BUY | SELL (só as previsões cujo lado mais provável é este)
     * @param minProb  P mínima do lado mais provável (0 = qualquer)
     * @param skipFlat pula quando LATERAL é o mais provável (como a roleta de tendência)
     */
    public record Query(String market, String symbol, String period, int horizon, String model, double lot,
                        String side, double minProb, boolean skipFlat) {
    }

    public record Trade(Instant moment, String symbol, String side, double pSide, String label, double r, Double usd) {
    }

    public record Line(String symbol, int trades, int wins, int losses, double sumR, double usd) {
    }

    public record Result(Instant from, int trades, int wins, int losses, double sumR, Double avgR, double usd,
                         int pending, int skippedFlat, int filtered, boolean moneyStop, List<Line> bySymbol,
                         List<Trade> list) {
    }

    public Result run(Query q) {
        Instant from = from(q.period(), Instant.now());
        RiskSettings risk = riskHolder.get();
        RiskSettings.MoneyExits money = risk.money();
        double stopAtr = risk.exitsFor(Market.fromCode(q.market())).stopAtr();
        int pending = 0, skippedFlat = 0, filtered = 0;
        List<Trade> trades = new ArrayList<>();
        Map<String, double[]> by = new TreeMap<>();   // symbol → {trades, wins, losses, sumR, usd}
        for (PredictionRepository.Prediction p : predictions.history(q.market(), q.symbol(), from, 20_000)) {
            if (!p.model().equals(q.model()) || p.horizonMin() != q.horizon() || "MANUAL".equals(p.trigger())) continue;
            if (p.label() == null || p.rBuy() == null || p.rSell() == null) {
                pending++;
                continue;
            }
            Decision d = decide(p, q);
            if (d == null) {
                filtered++;
                continue;
            }
            if (d.flat()) {
                skippedFlat++;
                continue;
            }
            double r = "BUY".equals(d.side()) ? p.rBuy() : p.rSell();
            Double usd = riskUsd(p, q.lot(), money, stopAtr);
            if (usd != null) usd *= r;
            trades.add(new Trade(p.momentUtc(), p.symbol(), d.side(), d.pSide(), p.label(), r, usd));
            double[] a = by.computeIfAbsent(p.symbol(), k -> new double[5]);
            a[0]++;
            if (r > 0) a[1]++; else a[2]++;
            a[3] += r;
            a[4] += usd == null ? 0 : usd;
        }
        int wins = (int) trades.stream().filter(t -> t.r() > 0).count();
        double sumR = trades.stream().mapToDouble(Trade::r).sum();
        double usd = trades.stream().mapToDouble(t -> t.usd() == null ? 0 : t.usd()).sum();
        List<Line> lines = new ArrayList<>();
        by.forEach((s, a) -> lines.add(new Line(s, (int) a[0], (int) a[1], (int) a[2], a[3], a[4])));
        return new Result(from, trades.size(), wins, trades.size() - wins, sumR,
                trades.isEmpty() ? null : sumR / trades.size(), usd, pending, skippedFlat, filtered, money.enabled(),
                lines, trades.stream().limit(300).toList());
    }

    /** O lado que a previsão aposta; null = fora dos filtros; flat = LATERAL mais provável (pulada). */
    record Decision(String side, double pSide, boolean flat) {
    }

    static Decision decide(PredictionRepository.Prediction p, Query q) {
        double top = Math.max(p.pUp(), p.pDown());
        if (p.pUp() == p.pDown()) return null;
        String side = p.pUp() > p.pDown() ? "BUY" : "SELL";
        if (!"all".equals(q.side()) && !q.side().equals(side)) return null;
        if (top < q.minProb()) return null;
        return new Decision(side, top, q.skipFlat() && p.pFlat() >= top);
    }

    /** Perda no stop desta previsão em US$: o stop em US$, se ligado; senão stop-atr × ATR com o lote (≥ o mínimo). */
    private Double riskUsd(PredictionRepository.Prediction p, double lot, RiskSettings.MoneyExits money, double stopAtr) {
        if (money.enabled()) return money.stopUsd();
        Instrument inst = orders.instrument(p.symbol());
        if (inst == null || p.atr() == null) return null;
        return inst.lossPerLot(stopAtr * p.atr()) * Math.max(lot, inst.minLot());
    }

    static Instant from(String period, Instant now) {
        return switch (period == null ? "48h" : period) {
            case "1h" -> now.minus(Duration.ofHours(1));
            case "today" -> LocalDate.ofInstant(now, ZoneOffset.UTC).atStartOfDay().toInstant(ZoneOffset.UTC);
            case "24h" -> now.minus(Duration.ofHours(24));
            case "7d" -> now.minus(Duration.ofDays(7));
            case "30d" -> now.minus(Duration.ofDays(30));
            default -> now.minus(Duration.ofHours(48));
        };
    }

}
