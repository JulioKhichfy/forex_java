package com.jevforex.typesafe.signal;

import com.jevforex.typesafe.model.JevResponse;

/**
 * Converte a resposta do conjunto cb-text-v1 num sinal numérico para a moeda do emissor
 * (documento mestre, capítulo 9).
 *
 * <pre>
 * stance    = P(hawkish) − P(dovish)                         (−1 … +1)
 * magnitude = stance_shift normalizado                       (0 … 1; níveis do Score começam em 0)
 * peso      = confidence(policy_stance) × P(market_relevant) (0 se confiança &lt; mínimo)
 * sinal     = stance × (0,5 + 0,5 × magnitude) × peso
 * </pre>
 * Positivo = favorece a moeda do emissor (tom mais duro); negativo = enfraquece.
 */
public record CbTextSignal(double stance, double magnitude, double confidence, double marketRelevant,
                           double weight, double signal) {

    public static CbTextSignal from(JevResponse r, double minConfidence) {
        var stanceAns = r.choice("policy_stance")
                .orElseThrow(() -> new IllegalArgumentException("resposta sem policy_stance"));
        double stance = stanceAns.p("hawkish") - stanceAns.p("dovish");
        double magnitude = r.score("stance_shift").map(JevResponse.ScoreAnswer::normalized).orElse(0.0);
        double relevant = r.noul("market_relevant").orElse(0.0);
        double conf = stanceAns.confidence();
        double weight = (Double.isNaN(conf) || conf < minConfidence) ? 0.0 : conf * relevant;
        double signal = stance * (0.5 + 0.5 * magnitude) * weight;
        return new CbTextSignal(stance, magnitude, conf, relevant, weight, signal);
    }
}
