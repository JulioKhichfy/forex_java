package com.jevforex.features.scan;

import java.util.ArrayList;
import java.util.List;

/**
 * Padrão de três topos (pedido do operador em 20/10/2026), com a MESMA definição do teste que o avaliou:
 *
 * <pre>
 * pivô de alta    = máxima maior que as das {@value #K} barras de cada lado (só barras fechadas)
 * três topos      = três pivôs de alta consecutivos em até {@value #MAX_SPAN} barras, cada intervalo ≥ 3 barras,
 *                   na mesma altura: maior − menor ≤ 0,5 ATR14
 * linha de pescoço = a menor mínima entre o 1º e o 3º topo; altura = média dos topos − pescoço ≥ 1 ATR
 * alta antes      = a mínima das 30 barras antes do 1º topo está 2 ATR abaixo dele
 * rompimento      = fechamento abaixo do pescoço até {@value #BREAK_WAIT} barras depois do 3º topo, sem passar dos topos
 * </pre>
 *
 * Testado em 2022–2026 (M5/M15, todos os mercados, com spread): a venda deu E[R] negativo em todos, igual ou pior que
 * vender num momento qualquer. É alerta, não sinal validado.
 */
public final class TripleTop {

    static final int K = 3, MAX_SPAN = 60, BREAK_WAIT = 20, PRIOR = 30;
    static final double TOLERANCE_ATR = 0.5, MIN_HEIGHT_ATR = 1.0, PRIOR_RISE_ATR = 2.0;

    private TripleTop() {
    }

    /**
     * @param top     o maior dos três topos (o stop estrutural fica acima dele)
     * @param neck    linha de pescoço
     * @param height  média dos topos − pescoço (a medida do alvo clássico: pescoço − altura)
     * @param barsAgo barras fechadas desde o 3º topo
     * @param broken  já fechou abaixo do pescoço
     * @param brokeAgo barras desde o rompimento (null = não rompeu)
     */
    public record Pattern(double top, double neck, double height, double atr, int barsAgo, boolean broken,
                         Integer brokeAgo) {
    }

    /** O padrão mais recente que ainda vale (3º topo há até BREAK_WAIT barras, preço não passou dos topos), ou null. */
    public static Pattern latest(List<Bar> all) {
        List<Bar> b = all.stream().filter(Bar::closed).toList();
        int n = b.size();
        if (n < PRIOR + MAX_SPAN + K + 15) return null;
        double[] atr = atr14(b);
        List<Integer> ph = new ArrayList<>();
        for (int i = K; i < n - K; i++) {
            double h = b.get(i).high();
            boolean pivot = true;
            for (int j = i - K; j <= i + K && pivot; j++) {
                if (j < i && b.get(j).high() >= h) pivot = false;      // primeira ocorrência do máximo
                if (j > i && b.get(j).high() > h) pivot = false;
            }
            if (pivot) ph.add(i);
        }
        for (int x = ph.size() - 1; x >= 2; x--) {
            int p1 = ph.get(x - 2), p2 = ph.get(x - 1), p3 = ph.get(x);
            int barsAgo = n - 1 - p3;
            if (barsAgo > BREAK_WAIT + K) return null;   // o mais recente já é velho: os anteriores também
            if (p3 - p1 > MAX_SPAN || p2 - p1 < 3 || p3 - p2 < 3 || p1 < PRIOR) continue;
            double a = atr[p3];
            double t1 = b.get(p1).high(), t2 = b.get(p2).high(), t3 = b.get(p3).high();
            double top = Math.max(t1, Math.max(t2, t3)), low = Math.min(t1, Math.min(t2, t3));
            if (top - low > TOLERANCE_ATR * a) continue;
            double neck = Double.MAX_VALUE, highBetween = 0;
            for (int i = p1; i <= p3; i++) {
                neck = Math.min(neck, b.get(i).low());
                highBetween = Math.max(highBetween, b.get(i).high());
            }
            if (highBetween > top + 1e-12) continue;
            double height = (t1 + t2 + t3) / 3 - neck;
            if (height < MIN_HEIGHT_ATR * a) continue;
            double priorLow = Double.MAX_VALUE;
            for (int i = p1 - PRIOR; i < p1; i++) priorLow = Math.min(priorLow, b.get(i).low());
            if (priorLow > t1 - PRIOR_RISE_ATR * a) continue;
            Integer broke = null;
            boolean invalid = false;
            for (int j = p3 + 1; j < n; j++) {
                if (b.get(j).high() > top) {
                    invalid = true;
                    break;
                }
                if (broke == null && j <= p3 + BREAK_WAIT && b.get(j).close() < neck) broke = j;
            }
            if (invalid) continue;
            if (broke == null && barsAgo > BREAK_WAIT) continue;   // não rompeu a tempo
            return new Pattern(top, neck, height, a, barsAgo, broke != null, broke == null ? null : n - 1 - broke);
        }
        return null;
    }

    static double[] atr14(List<Bar> b) {
        int n = b.size();
        double[] tr = new double[n], a = new double[n];
        for (int i = 0; i < n; i++) {
            double pc = i == 0 ? b.get(0).close() : b.get(i - 1).close();
            Bar x = b.get(i);
            tr[i] = Math.max(x.high() - x.low(), Math.max(Math.abs(x.high() - pc), Math.abs(x.low() - pc)));
        }
        double s = 0;
        for (int i = 0; i < Math.min(14, n); i++) s += tr[i];
        for (int i = 0; i < Math.min(14, n); i++) a[i] = s / Math.min(14, n);
        for (int i = 14; i < n; i++) a[i] = a[i - 1] + (tr[i] - a[i - 1]) / 14;
        return a;
    }
}
