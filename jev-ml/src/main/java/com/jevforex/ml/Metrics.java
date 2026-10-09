package com.jevforex.ml;

import java.util.Arrays;

/** Métricas fora da amostra (documento mestre, capítulo 11). */
public final class Metrics {

    private Metrics() {
    }

    /** Log loss multiclasse: −média de ln P(classe verdadeira). Menor é melhor. */
    public static double logLoss(double[][] p, int[] y) {
        double s = 0;
        for (int i = 0; i < y.length; i++) s -= Math.log(Math.max(1e-15, p[i][y[i]]));
        return y.length == 0 ? Double.NaN : s / y.length;
    }

    /** AUC de "é da classe c" contra o resto (Mann-Whitney, empates contam meio). 0,5 = acaso. */
    public static double auc(double[][] p, int[] y, int c) {
        int n = y.length;
        Integer[] idx = new Integer[n];
        for (int i = 0; i < n; i++) idx[i] = i;
        Arrays.sort(idx, (a, b) -> Double.compare(p[a][c], p[b][c]));
        long pos = 0;
        for (int v : y) if (v == c) pos++;
        long neg = n - pos;
        if (pos == 0 || neg == 0) return Double.NaN;
        double rankSum = 0;
        int i = 0;
        while (i < n) {
            int j = i;
            while (j + 1 < n && p[idx[j + 1]][c] == p[idx[i]][c]) j++;
            double avgRank = (i + j) / 2.0 + 1;   // posto médio dos empatados
            for (int k = i; k <= j; k++) if (y[idx[k]] == c) rankSum += avgRank;
            i = j + 1;
        }
        return (rankSum - pos * (pos + 1) / 2.0) / ((double) pos * neg);
    }

    /**
     * Operações simuladas com o gate 4: compra se P(ALTA) ≥ minProb e P(ALTA) − P(QUEDA) ≥ minMargin
     * (venda simétrica). Resultado em R = (resultado no horizonte, já com o spread, em ATR) ÷ stop em ATR.
     * Aproximação: não simula stop/alvo dentro do horizonte (o passo 5 simula).
     *
     * @param n              operações
     * @param expectancyR    média em R
     * @param profitFactor   ganhos ÷ perdas
     * @param maxDrawdownPct pior queda da curva somando R × risco por trade (%)
     * @param hitRate        fração de operações com resultado positivo
     */
    public record Trades(int n, double expectancyR, double profitFactor, double maxDrawdownPct, double hitRate) {
    }

    /** As linhas precisam estar em ordem cronológica (para o drawdown). */
    public static Trades trades(double[][] p, double[] yBuy, double[] ySell, double minProb, double minMargin,
                                double stopAtr, double riskPct) {
        int n = 0, wins = 0;
        double sum = 0, gains = 0, losses = 0, equity = 0, peak = 0, maxDd = 0;
        for (int i = 0; i < p.length; i++) {
            double up = p[i][Dataset.UP], down = p[i][Dataset.DOWN];
            double result;
            if (up >= minProb && up - down >= minMargin) result = yBuy[i];
            else if (down >= minProb && down - up >= minMargin) result = ySell[i];
            else continue;
            double r = result / stopAtr;
            n++;
            sum += r;
            if (r > 0) {
                wins++;
                gains += r;
            } else {
                losses -= r;
            }
            equity += r * riskPct;
            peak = Math.max(peak, equity);
            maxDd = Math.max(maxDd, peak - equity);
        }
        return new Trades(n, n == 0 ? Double.NaN : sum / n, losses == 0 ? Double.NaN : gains / losses, maxDd,
                n == 0 ? Double.NaN : (double) wins / n);
    }

    /** Probabilidades constantes = frequência das classes no treino (a linha de base "sem modelo"). */
    public static double[][] priors(int[] yTrain, int rows) {
        double[] f = new double[Dataset.CLASSES.size()];
        for (int v : yTrain) f[v]++;
        for (int k = 0; k < f.length; k++) f[k] = (f[k] + 1) / (yTrain.length + f.length);
        double[][] out = new double[rows][];
        Arrays.fill(out, f);
        return out;
    }
}
