package com.jevforex.app.config;

import com.jevforex.ml.ExperimentConfig;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.LocalDate;
import java.util.List;

/** Bloco {@code experiment} do application.yml (passo 3d). Ver {@link ExperimentConfig}. */
@ConfigurationProperties(prefix = "experiment")
public record ExperimentProperties(List<Integer> horizons, LocalDate from, Integer trainMonths, Integer testMonths,
                                   Integer embargoDays, Integer lockboxMonths, ExperimentConfig.Gbm gbm,
                                   ExperimentConfig.Decision decision, Double stopAtr, Double riskPerTradePct,
                                   Integer threads, Long seed) {

    /** Valores ausentes ficam com os do documento mestre; o fset vem do bloco features. */
    public ExperimentConfig toConfig(String fset) {
        ExperimentConfig d = ExperimentConfig.defaults();
        return new ExperimentConfig(fset,
                horizons == null ? d.horizons() : horizons,
                from == null ? d.from() : from,
                trainMonths == null ? d.trainMonths() : trainMonths,
                testMonths == null ? d.testMonths() : testMonths,
                embargoDays == null ? d.embargoDays() : embargoDays,
                lockboxMonths == null ? d.lockboxMonths() : lockboxMonths,
                gbm == null ? d.gbm() : gbm,
                decision == null ? d.decision() : decision,
                stopAtr == null ? d.stopAtr() : stopAtr,
                riskPerTradePct == null ? d.riskPerTradePct() : riskPerTradePct,
                threads == null || threads <= 0 ? d.threads() : threads,
                seed == null ? d.seed() : seed);
    }
}
