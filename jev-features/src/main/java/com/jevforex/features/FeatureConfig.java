package com.jevforex.features;

import java.util.List;
import java.util.Map;

/**
 * Parâmetros das features e dos labels (bloco {@code features} do application.yml).
 * Mudou algo que altera o significado das features → nova versão de {@code fset} (ex.: v2).
 *
 * @param fset                 versão do conjunto de features (vai no caminho do gold: fset=v2)
 * @param entryDelayMinutes    atraso de execução no label: entra no preço da barra que abre em t + atraso
 * @param horizonsMinutes      horizontes dos labels (documento mestre: 15 e 60)
 * @param labelThresholdAtr    ALTA/QUEDA se o resultado depois dos custos passar disto (em ATR)
 * @param eventLagMinutes      momento de decisão = horário do evento + isto (cap. 11: 2 min)
 * @param controlHoursUtc      horas UTC (seg a sex) com momento de controle "sem notícia"
 * @param importanceWeights    peso da surpresa por importância do evento (ausente = ignora)
 * @param surpriseDecayMinutes τ do decaimento da surpresa (cap. 10: 60 min)
 * @param sigmaWindow          divulgações anteriores do mesmo evento para o σ da surpresa (cap. 10: 24)
 * @param sigmaMinHistory      mínimo de divulgações anteriores para calcular z
 * @param negativePolarity     trechos de event_code em que valor maior ENFRAQUECE a moeda (desemprego…)
 * @param text                 grupo C: sinais do Jev por moeda (null = sem grupo C)
 */
public record FeatureConfig(String fset, int entryDelayMinutes, List<Integer> horizonsMinutes,
                            double labelThresholdAtr, int eventLagMinutes, List<Integer> controlHoursUtc,
                            Map<String, Double> importanceWeights, int surpriseDecayMinutes, int sigmaWindow,
                            int sigmaMinHistory, List<String> negativePolarity, Text text) {

    /**
     * Grupo C (documento mestre, capítulo 10): S_moeda(t) = Σ sinal_i × exp(−(t − t_i) / τ), só documentos com
     * available_utc ≤ t.
     *
     * @param qset            conjunto de perguntas cujos sinais entram (gold/currency_signals/…/qset=…)
     * @param model           versão do Jev (…/model=…)
     * @param tauShortHours   τ curto (reação): 6 h
     * @param tauLongHours    τ longo (contexto da semana): 72 h
     * @param minRelevance    documento "relevante" para contagem e recência (market_relevant)
     */
    public record Text(String qset, String model, double tauShortHours, double tauLongHours, double minRelevance) {
        public Text {
            if (qset == null || qset.isBlank() || model == null || model.isBlank()) {
                throw new IllegalArgumentException("features.text: qset e model são obrigatórios");
            }
            if (tauShortHours <= 0 || tauLongHours <= 0) throw new IllegalArgumentException("features.text: τ deve ser > 0");
        }

        /** Caminho dos sinais no lake. */
        public String signalsPath() {
            return "gold/currency_signals/market=fx/qset=" + qset + "/model=" + model;
        }

        /** Surpresa de tom por divulgação (jev-signals). */
        public String tonePath() {
            return "gold/tone_surprises/market=fx/qset=" + qset + "/model=" + model;
        }
    }

    public FeatureConfig {
        if (fset == null || !fset.matches("v\\d+")) throw new IllegalArgumentException("features.fset deve ser v1, v2…");
        if (entryDelayMinutes < 1) throw new IllegalArgumentException("features.entry-delay-minutes deve ser >= 1");
        if (horizonsMinutes == null || horizonsMinutes.isEmpty()) horizonsMinutes = List.of(15, 60);
        if (labelThresholdAtr <= 0) throw new IllegalArgumentException("features.label-threshold-atr deve ser > 0");
        if (importanceWeights == null || importanceWeights.isEmpty()) importanceWeights = Map.of("HIGH", 1.0, "MODERATE", 0.5);
        if (controlHoursUtc == null) controlHoursUtc = List.of();
        if (negativePolarity == null) negativePolarity = List.of();
        if (sigmaWindow < 2 || sigmaMinHistory < 2 || sigmaMinHistory > sigmaWindow) {
            throw new IllegalArgumentException("features.calendar: precisa 2 <= sigma-min-history <= sigma-window");
        }
        horizonsMinutes = List.copyOf(horizonsMinutes);
        controlHoursUtc = List.copyOf(controlHoursUtc);
        importanceWeights = Map.copyOf(importanceWeights);
        negativePolarity = List.copyOf(negativePolarity);
    }

    /**
     * Valores do documento mestre (capítulos 10 e 11). fset v2 = v1 + grupo C (tom do Jev);
     * v3 = v2 + surpresa de tom (tom − tom da divulgação anterior do mesmo banco e tipo).
     */
    public static FeatureConfig defaults() {
        return new FeatureConfig("v3", 1, List.of(15, 60), 0.5, 2,
                List.of(0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20),
                Map.of("HIGH", 1.0, "MODERATE", 0.5), 60, 24, 8,
                List.of("unemployment", "jobless", "claims"),
                new Text("cb-text-v1", "jev-1.13.0", 6, 72, 0.5));
    }
}
