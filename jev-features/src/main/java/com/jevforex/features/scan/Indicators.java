package com.jevforex.features.scan;

import java.util.Arrays;
import java.util.List;

/** Indicadores clássicos sobre uma série de barras. NaN = ainda sem barras suficientes. */
public final class Indicators {

    private Indicators() {
    }

    /** ATR = média simples do true range das últimas n barras (o mesmo cálculo das features). */
    public static double[] atr(List<Bar> bars, int n) {
        double[] tr = new double[bars.size()];
        for (int i = 0; i < tr.length; i++) {
            Bar b = bars.get(i);
            tr[i] = i == 0 ? Double.NaN : Math.max(b.high() - b.low(),
                    Math.max(Math.abs(b.high() - bars.get(i - 1).close()), Math.abs(b.low() - bars.get(i - 1).close())));
        }
        double[] out = nan(tr.length);
        for (int i = n; i < tr.length; i++) {
            double s = 0;
            for (int k = i - n + 1; k <= i; k++) s += tr[k];
            out[i] = s / n;
        }
        return out;
    }

    /** RSI de Wilder (o do MetaTrader): média inicial simples e depois suavização 1/n. */
    public static double[] rsi(double[] c, int n) {
        double[] out = nan(c.length);
        if (c.length <= n) return out;
        double gain = 0, loss = 0;
        for (int i = 1; i <= n; i++) {
            double d = c[i] - c[i - 1];
            gain += Math.max(d, 0);
            loss += Math.max(-d, 0);
        }
        gain /= n;
        loss /= n;
        out[n] = rsiOf(gain, loss);
        for (int i = n + 1; i < c.length; i++) {
            double d = c[i] - c[i - 1];
            gain = (gain * (n - 1) + Math.max(d, 0)) / n;
            loss = (loss * (n - 1) + Math.max(-d, 0)) / n;
            out[i] = rsiOf(gain, loss);
        }
        return out;
    }

    private static double rsiOf(double gain, double loss) {
        if (loss == 0) return gain == 0 ? 50 : 100;
        return 100 - 100 / (1 + gain / loss);
    }

    public static double[] sma(double[] x, int n) {
        double[] out = nan(x.length);
        for (int i = n - 1; i < x.length; i++) {
            double s = 0;
            for (int k = i - n + 1; k <= i; k++) s += x[k];
            out[i] = s / n;
        }
        return out;
    }

    /** Desvio padrão populacional de n barras (o das bandas de Bollinger do MetaTrader). */
    public static double[] std(double[] x, int n) {
        double[] m = sma(x, n);
        double[] out = nan(x.length);
        for (int i = n - 1; i < x.length; i++) {
            double s = 0;
            for (int k = i - n + 1; k <= i; k++) s += (x[k] - m[i]) * (x[k] - m[i]);
            out[i] = Math.sqrt(s / n);
        }
        return out;
    }

    /** Média exponencial com semente na média simples das n primeiras. */
    public static double[] ema(double[] x, int n) {
        double[] out = nan(x.length);
        if (x.length < n) return out;
        double a = 2.0 / (n + 1), e = 0;
        for (int i = 0; i < n; i++) e += x[i];
        e /= n;
        out[n - 1] = e;
        for (int i = n; i < x.length; i++) {
            e = a * x[i] + (1 - a) * e;
            out[i] = e;
        }
        return out;
    }

    public static double median(double[] x) {
        if (x.length == 0) return Double.NaN;
        double[] s = x.clone();
        Arrays.sort(s);
        return s.length % 2 == 1 ? s[s.length / 2] : (s[s.length / 2 - 1] + s[s.length / 2]) / 2;
    }

    private static double[] nan(int n) {
        double[] a = new double[n];
        Arrays.fill(a, Double.NaN);
        return a;
    }
}
