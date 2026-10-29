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
 * @param controlMinutesByMarket minutos da hora com momento de controle por mercado (padrão [0] = só a hora cheia;
 *                             [0, 15, 30, 45] = a cada 15 min, pedido do operador em 22/10/2026 — os de minuto ≠ 0 são QUARTER)
 * @param scheduleByMarket     janela ESTENDIDA de horas cheias por mercado (pedido do operador em 28/10/2026: prever com o
 *                             mercado aberto, bitcoin 24/7). A janela de sempre (seg a sex, {@code controlHoursUtc}, sem
 *                             sexta ≥ 19 h) continua CONTROL; as horas cheias a mais viram EXTRA, que o Dataset deixa de
 *                             fora por padrão (walk-forward, Laboratório e modelo de produção não mudam) até um estudo
 *                             aprovar. Hora com o mercado fechado sai no filtro de dado velho.
 */
public record FeatureConfig(String fset, int entryDelayMinutes, List<Integer> horizonsMinutes,
                            double labelThresholdAtr, int eventLagMinutes, List<Integer> controlHoursUtc,
                            Map<String, Double> importanceWeights, int surpriseDecayMinutes, int sigmaWindow,
                            int sigmaMinHistory, List<String> negativePolarity, Text text,
                            Map<String, List<Integer>> controlMinutesByMarket, Map<String, Schedule> scheduleByMarket) {

    /** Sem a janela estendida (o de sempre). */
    public FeatureConfig(String fset, int entryDelayMinutes, List<Integer> horizonsMinutes, double labelThresholdAtr,
                         int eventLagMinutes, List<Integer> controlHoursUtc, Map<String, Double> importanceWeights,
                         int surpriseDecayMinutes, int sigmaWindow, int sigmaMinHistory, List<String> negativePolarity,
                         Text text, Map<String, List<Integer>> controlMinutesByMarket) {
        this(fset, entryDelayMinutes, horizonsMinutes, labelThresholdAtr, eventLagMinutes, controlHoursUtc,
                importanceWeights, surpriseDecayMinutes, sigmaWindow, sigmaMinHistory, negativePolarity, text,
                controlMinutesByMarket, Map.of());
    }

    /**
     * Janela estendida de um mercado.
     *
     * @param days             dias ISO (1 = segunda … 7 = domingo)
     * @param hours            horas UTC
     * @param fridayCutoffHour sexta a partir desta hora: sem momento (o fim de semana fecha o forex às ~21 h); null = sem corte
     */
    public record Schedule(List<Integer> days, List<Integer> hours, Integer fridayCutoffHour) {
        public Schedule {
            days = days == null ? List.of(1, 2, 3, 4, 5) : List.copyOf(days);
            hours = hours == null ? List.of() : List.copyOf(hours);
            for (Integer d : days) if (d == null || d < 1 || d > 7) throw new IllegalArgumentException("schedule: dias de 1 a 7");
            for (Integer h : hours) if (h == null || h < 0 || h > 23) throw new IllegalArgumentException("schedule: horas de 0 a 23");
        }
    }

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
        controlMinutesByMarket = controlMinutesByMarket == null ? Map.of() : Map.copyOf(controlMinutesByMarket);
        scheduleByMarket = scheduleByMarket == null ? Map.of() : Map.copyOf(scheduleByMarket);
        for (var e : controlMinutesByMarket.entrySet()) {
            for (Integer m : e.getValue()) {
                if (m == null || !List.of(0, 15, 30, 45).contains(m)) {
                    throw new IllegalArgumentException("features.control-minutes-by-market." + e.getKey() + ": só 0, 15, 30 ou 45");
                }
            }
        }
    }

    /** Minutos da hora com momento de controle neste mercado (padrão: só a hora cheia). */
    public List<Integer> controlMinutes(String market) {
        List<Integer> m = controlMinutesByMarket.get(market);
        return m == null || m.isEmpty() ? List.of(0) : m;
    }

    /** A janela estendida do mercado, ou null (só a janela de sempre). */
    public Schedule schedule(String market) {
        return scheduleByMarket.get(market);
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
                new Text("cb-text-v1", "jev-1.13.0", 6, 72, 0.5), Map.of());
    }
}
