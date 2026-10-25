package com.jevforex.features.scan;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;

/**
 * Momentum após hora grande (passo 12b, 25/10/2026; estudo em tools/estudo_momentum.py, a MESMA definição): na hora
 * cheia, se |ln(fechamento / abertura)| da hora que acabou de fechar está no topo de {@code topPct}% das
 * {@code lookback} horas anteriores, o sinal é seguir o movimento. Stop e alvo em ATR(14) de 1 h (média simples do true
 * range, a da hora do sinal inclusive). Só o bitcoin teve vantagem (37/54 configurações positivas na escolha e na
 * confirmação); forex, índices e energia, negativo.
 *
 * <p>Point-in-time: só barras FECHADAS; o limiar usa as horas anteriores à do sinal.</p>
 */
public final class MomentumRule {

    private MomentumRule() {
    }

    /**
     * @param barStart  início da hora do sinal (UTC); a entrada é no fim dela
     * @param side      BUY | SELL (a favor do movimento)
     * @param ret       ln(fechamento / abertura) da hora
     * @param threshold o limiar de |ret| (quantil 1 − topPct/100 das horas anteriores)
     * @param atr       ATR(14) de 1 h no preço
     */
    public record Signal(LocalDateTime barStart, String side, double ret, double threshold, double atr) {
    }

    /**
     * Avalia a ÚLTIMA barra da lista.
     *
     * @param h1 barras de 1 h em ordem, só as fechadas
     * @return o sinal, ou null (sem barras bastantes, movimento abaixo do limiar ou ATR zero)
     */
    public static Signal evaluate(List<Bar> h1, int lookback, double topPct, int atrPeriod) {
        int n = h1.size();
        if (n < lookback + 1 || n < atrPeriod + 1) return null;
        Bar last = h1.get(n - 1);
        if (!(last.open() > 0) || !(last.close() > 0)) return null;
        double ret = Math.log(last.close() / last.open());
        double[] past = new double[lookback];
        for (int k = 0; k < lookback; k++) {
            Bar b = h1.get(n - 1 - lookback + k);
            past[k] = b.open() > 0 && b.close() > 0 ? Math.abs(Math.log(b.close() / b.open())) : 0;
        }
        double thr = quantile(past, 1 - topPct / 100.0);
        if (!(Math.abs(ret) >= thr) || ret == 0) return null;
        double atr = atr(h1, atrPeriod);
        if (!(atr > 0)) return null;
        return new Signal(last.t(), ret > 0 ? "BUY" : "SELL", ret, thr, atr);
    }

    /** ATR(period) na última barra: média simples dos true ranges das últimas {@code period} barras. */
    static double atr(List<Bar> h1, int period) {
        int n = h1.size();
        double sum = 0;
        for (int i = n - period; i < n; i++) {
            Bar b = h1.get(i), p = h1.get(i - 1);
            sum += Math.max(b.high() - b.low(), Math.max(Math.abs(b.high() - p.close()), Math.abs(b.low() - p.close())));
        }
        return sum / period;
    }

    /** Quantil com interpolação linear (o padrão do numpy.quantile, usado no estudo). */
    static double quantile(double[] values, double q) {
        double[] v = values.clone();
        Arrays.sort(v);
        double pos = q * (v.length - 1);
        int lo = (int) Math.floor(pos);
        int hi = Math.min(lo + 1, v.length - 1);
        return v[lo] + (pos - lo) * (v[hi] - v[lo]);
    }
}
