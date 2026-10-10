package com.jevforex.typesafe.signal;

import com.jevforex.typesafe.model.JevResponse;

/**
 * Converte a resposta do conjunto cb-text-v2 (comparação com a comunicação anterior) num sinal de MUDANÇA para
 * a moeda do emissor. Mesma forma do {@link CbTextSignal}, sobre a mudança e não sobre o tom:
 *
 * <pre>
 * shift     = P(more_hawkish) − P(more_dovish)                   (−1 … +1)
 * magnitude = shift_size normalizado                             (0 … 1; níveis do Score começam em 0)
 * peso      = confidence(relative_stance) × P(market_relevant)   (0 se confiança &lt; mínimo)
 * sinal     = shift × (0,5 + 0,5 × magnitude) × peso
 * </pre>
 * Positivo = a mensagem endureceu em relação à anterior (favorece a moeda do emissor).
 */
public record CbShiftSignal(double shift, double magnitude, double confidence, double marketRelevant,
                            double weight, double signal) {

    public static CbShiftSignal from(JevResponse r, double minConfidence) {
        var ans = r.choice("relative_stance")
                .orElseThrow(() -> new IllegalArgumentException("resposta sem relative_stance"));
        double shift = ans.p("more_hawkish") - ans.p("more_dovish");
        double magnitude = r.score("shift_size").map(JevResponse.ScoreAnswer::normalized).orElse(0.0);
        double relevant = r.noul("market_relevant").orElse(0.0);
        double conf = ans.confidence();
        double weight = (Double.isNaN(conf) || conf < minConfidence) ? 0.0 : conf * relevant;
        return new CbShiftSignal(shift, magnitude, conf, relevant, weight, shift * (0.5 + 0.5 * magnitude) * weight);
    }
}
