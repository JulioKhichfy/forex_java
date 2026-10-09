package com.jevforex.ml;

import java.time.LocalDate;
import java.util.List;

/**
 * Experimento A/B (C no passo 4) com walk-forward — documento mestre, capítulo 11.
 *
 * @param fset           versão das features no gold
 * @param horizons       horizontes avaliados (minutos)
 * @param from           início do período comum (os 7 pares com dados)
 * @param trainMonths    janela de treino (24 meses)
 * @param testMonths     tamanho de cada teste (1 mês); o fold seguinte avança isso
 * @param embargoDays    só treina com labels conhecidos até (início do teste − embargo)
 * @param lockboxMonths  últimos meses completos reservados (cofre): NUNCA entram no walk-forward
 * @param gbm            hiperparâmetros fixos do gradient boosting (v1; a grade fica para o passo 5)
 * @param decision       regras do gate 4 para simular operações
 * @param stopAtr        stop em ATR: R = resultado ÷ stopAtr
 * @param riskPerTradePct risco por trade para o drawdown simulado
 * @param threads        folds treinados em paralelo
 * @param seed           semente do subsample (reprodutível)
 * @param models         modelos comparados (A = preço; B = + calendário; C = + texto do Jev); ver Dataset.MODELS
 */
public record ExperimentConfig(String fset, List<Integer> horizons, LocalDate from, int trainMonths, int testMonths,
                               int embargoDays, int lockboxMonths, Gbm gbm, Decision decision, double stopAtr,
                               double riskPerTradePct, int threads, long seed, List<String> models) {

    public record Gbm(int ntrees, int maxDepth, int maxNodes, int nodeSize, double shrinkage, double subsample) {
    }

    /** Opera se P(classe) ≥ minProb e P(classe) − P(oposta) ≥ minMargin (cap. 12, gate 4). */
    public record Decision(double minProb, double minMargin) {
    }

    public ExperimentConfig {
        if (trainMonths < 1 || testMonths < 1 || embargoDays < 0 || lockboxMonths < 0) {
            throw new IllegalArgumentException("experiment: meses/dias inválidos");
        }
        horizons = List.copyOf(horizons);
        if (threads < 1) threads = 1;
        if (models == null || models.isEmpty()) models = List.of("A", "B", "C");
        for (String m : models) {
            if (!Dataset.MODELS.containsKey(m)) throw new IllegalArgumentException("experiment.models: modelo desconhecido " + m);
        }
        models = List.copyOf(models);
    }

    public static ExperimentConfig defaults() {
        return new ExperimentConfig("v2", List.of(60, 15), LocalDate.parse("2021-12-01"), 24, 1, 1, 6,
                new Gbm(200, 4, 16, 50, 0.05, 0.7), new Decision(0.60, 0.35), 1.5, 0.5,
                Math.max(1, Runtime.getRuntime().availableProcessors() - 1), 19650218L, List.of("A", "B", "C"));
    }

    /** O mesmo experimento com outros horizontes. */
    public ExperimentConfig withHorizons(List<Integer> h) {
        return new ExperimentConfig(fset, h, from, trainMonths, testMonths, embargoDays, lockboxMonths, gbm, decision,
                stopAtr, riskPerTradePct, threads, seed, models);
    }
}
