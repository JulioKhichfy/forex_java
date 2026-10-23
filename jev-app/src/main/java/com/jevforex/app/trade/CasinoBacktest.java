package com.jevforex.app.trade;

import com.jevforex.features.scan.Bar;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

/**
 * Backtest do Cassino (pedido do operador em 23/10/2026): se a linha tivesse aberto a ordem no início de cada um dos
 * últimos 4 quartos de hora completos, o que teria acontecido — alvo, stop, saída por tempo ou ainda aberta.
 *
 * <p>Barras M1 do MT5 (preço bid). Compra entra no ask (abertura + spread) e sai no bid; venda entra no bid e sai no
 * ask (bid + spread). O spread é o de agora, igual em todas as barras. Alvo e stop na mesma barra contam como STOP
 * (não se sabe qual veio primeiro: a conta pessimista). Uma entrada por quarto, independentes entre si.</p>
 */
public final class CasinoBacktest {

    private CasinoBacktest() {
    }

    /**
     * @param side   BUY | SELL (null = sem lado: o "Deixa comigo!" sem previsão recente)
     * @param result ALVO | STOP | TEMPO | ABERTA | SEM_DADOS | SEM_LADO
     * @param usd    resultado em US$ com o lote (ABERTA: o da última barra)
     */
    public record Quarter(Instant start, String side, String result, Double usd, Integer minutes) {
    }

    /**
     * @param ref               agora (ou a última barra + 1 min, com o mercado fechado)
     * @param tpDist / slDist   distâncias em preço do alvo e do stop
     * @param spreadPx          spread em preço
     * @param closeAfterMinutes saída por tempo; 0 = sem
     * @param usdPerPrice       US$ por 1,0 de preço com o lote
     */
    public static List<Quarter> run(List<Bar> m1, Instant ref, boolean buy, double tpDist, double slDist,
                                    double spreadPx, int closeAfterMinutes, double usdPerPrice) {
        return run(m1, ref, t -> buy, tpDist, slDist, spreadPx, closeAfterMinutes, usdPerPrice);
    }

    /** @param buyAt o lado no início de cada quarto (true = compra; null = sem lado) */
    public static List<Quarter> run(List<Bar> m1, Instant ref, java.util.function.Function<Instant, Boolean> buyAt,
                                    double tpDist, double slDist, double spreadPx, int closeAfterMinutes,
                                    double usdPerPrice) {
        LocalDateTime now = LocalDateTime.ofInstant(ref, ZoneOffset.UTC);
        LocalDateTime current = now.truncatedTo(ChronoUnit.HOURS).plusMinutes(now.getMinute() / 15 * 15L);
        List<Quarter> out = new ArrayList<>();
        for (int k = 4; k >= 1; k--) {
            LocalDateTime start = current.minusMinutes(15L * k);
            Boolean buy = buyAt.apply(start.toInstant(ZoneOffset.UTC));
            out.add(buy == null ? new Quarter(start.toInstant(ZoneOffset.UTC), null, "SEM_LADO", null, null)
                    : one(m1, start, now, buy, tpDist, slDist, spreadPx, closeAfterMinutes, usdPerPrice));
        }
        return out;
    }

    private static Quarter one(List<Bar> m1, LocalDateTime start, LocalDateTime now, boolean buy, double tpDist,
                               double slDist, double spreadPx, int closeAfter, double usdPerPrice) {
        Instant at = start.toInstant(ZoneOffset.UTC);
        String side = buy ? "BUY" : "SELL";
        Double entry = null;
        Bar last = null;
        for (Bar b : m1) {
            if (b.t().isBefore(start)) continue;
            if (!b.t().isBefore(now)) break;
            int minutes = (int) ChronoUnit.MINUTES.between(start, b.t());
            if (entry == null) {
                if (minutes > 1) return new Quarter(at, side, "SEM_DADOS", null, null);   // sem a barra da entrada
                entry = buy ? b.open() + spreadPx : b.open();
            } else if (closeAfter > 0 && minutes >= closeAfter) {
                double exit = buy ? b.open() : b.open() + spreadPx;
                return new Quarter(at, side, "TEMPO", ((buy ? exit - entry : entry - exit)) * usdPerPrice, minutes);
            }
            // o lado de saída: compra sai no bid (a barra), venda no ask (barra + spread)
            double low = buy ? b.low() : b.low() + spreadPx, high = buy ? b.high() : b.high() + spreadPx;
            boolean stop = buy ? low <= entry - slDist : high >= entry + slDist;
            boolean target = buy ? high >= entry + tpDist : low <= entry - tpDist;
            if (stop) return new Quarter(at, side, "STOP", -slDist * usdPerPrice, minutes + 1);
            if (target) return new Quarter(at, side, "ALVO", tpDist * usdPerPrice, minutes + 1);
            last = b;
        }
        if (entry == null || last == null) return new Quarter(at, side, "SEM_DADOS", null, null);
        double exit = buy ? last.close() : last.close() + spreadPx;
        return new Quarter(at, side, "ABERTA", (buy ? exit - entry : entry - exit) * usdPerPrice,
                (int) ChronoUnit.MINUTES.between(start, last.t()) + 1);
    }
}
