package com.jevforex.features.scan;

import java.util.Arrays;

/**
 * "Baleia" no bitcoin (pedido do operador em 25/10/2026): um minuto dos futuros da Binance em que a compra (ou a venda)
 * A MERCADO foi muito maior que o normal e quase toda de um lado — volume de instituição ou de grande operador.
 *
 * <pre>
 * dominante  = max(compra agressiva, venda agressiva) do minuto
 * normal     = mediana do volume por minuto nas {@code lookback} minutos anteriores (24 h)
 * baleia     = dominante ≥ {@code multiple} × normal  e  |compra − venda| / volume ≥ {@code minImbalance}
 * </pre>
 *
 * Calibração (2025+): 10× e 0,5 dão ~5 por dia, ~US$ 66 mi no minuto. Descritivo: no minuto da baleia o preço anda
 * +14 a +22 bps a favor dela; DEPOIS (quando o alerta chega) a média é ~0 de 1 a 240 min (continua em 43–47%).
 */
public final class WhaleRule {

    private WhaleRule() {
    }

    /**
     * @param side     BUY (compra agressiva) | SELL
     * @param volume   o lado dominante, na moeda base (BTC)
     * @param multiple dominante ÷ volume normal do minuto
     * @param moveBps  variação do minuto (fechamento ÷ abertura − 1) a favor do lado, em pontos-base
     */
    public record Whale(String side, double volume, double multiple, double imbalance, double moveBps) {
    }

    /**
     * Avalia o minuto {@code i} (já fechado) com os {@code lookback} minutos anteriores como "normal".
     *
     * @return a baleia, ou null (minutos anteriores insuficientes, sem volume ou abaixo dos limites)
     */
    public static Whale evaluate(double[] volume, double[] takerBuy, double[] open, double[] close, int i, int lookback,
                                 double multiple, double minImbalance) {
        if (i < lookback || i >= volume.length || !(volume[i] > 0)) return null;
        double normal = median(Arrays.copyOfRange(volume, i - lookback, i));
        if (!(normal > 0)) return null;
        double buy = takerBuy[i], sell = volume[i] - takerBuy[i];
        double dom = Math.max(buy, sell);
        double imb = Math.abs(buy - sell) / volume[i];
        double mult = dom / normal;
        if (mult < multiple || imb < minImbalance) return null;
        String side = buy >= sell ? "BUY" : "SELL";
        double move = (close[i] / open[i] - 1) * 1e4 * ("BUY".equals(side) ? 1 : -1);
        return new Whale(side, dom, mult, imb, move);
    }

    static double median(double[] v) {
        double[] s = v.clone();
        Arrays.sort(s);
        int n = s.length;
        return n == 0 ? Double.NaN : n % 2 == 1 ? s[n / 2] : (s[n / 2 - 1] + s[n / 2]) / 2;
    }
}
