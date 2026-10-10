package com.jevforex.app.config;

import com.jevforex.core.risk.RiskSettings;
import com.jevforex.ml.ExperimentConfig;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.LocalDate;
import java.util.List;

/** Bloco {@code experiment} do application.yml (passo 3d). Ver {@link ExperimentConfig}. */
@ConfigurationProperties(prefix = "experiment")
public record ExperimentProperties(List<Integer> horizons, LocalDate from, Integer trainMonths, Integer testMonths,
                                   Integer embargoDays, Integer lockboxMonths, ExperimentConfig.Gbm gbm,
                                   ExperimentConfig.Decision decision, Integer lateMinutes, Double costStress,
                                   Double riskPerTradePct,
                                   Integer threads, Long seed, List<String> models) {

    /**
     * Valores ausentes ficam com os do documento mestre; o fset vem do bloco features e o stop/alvo das operações
     * simuladas de trading.risk.exits (os mesmos que o decision engine vai usar).
     */
    public ExperimentConfig toConfig(String fset, RiskSettings.Exits riskExits) {
        ExperimentConfig.Exits exits = new ExperimentConfig.Exits(riskExits.stopAtr(), riskExits.targetR(),
                lateMinutes == null ? 5 : lateMinutes);
        ExperimentConfig d = ExperimentConfig.defaults(exits);
        return new ExperimentConfig(fset,
                horizons == null ? d.horizons() : horizons,
                from == null ? d.from() : from,
                trainMonths == null ? d.trainMonths() : trainMonths,
                testMonths == null ? d.testMonths() : testMonths,
                embargoDays == null ? d.embargoDays() : embargoDays,
                lockboxMonths == null ? d.lockboxMonths() : lockboxMonths,
                gbm == null ? d.gbm() : gbm,
                decision == null ? d.decision() : decision,
                exits,
                costStress == null ? d.costStress() : costStress,
                riskPerTradePct == null ? d.riskPerTradePct() : riskPerTradePct,
                threads == null || threads <= 0 ? d.threads() : threads,
                seed == null ? d.seed() : seed,
                models == null ? d.models() : models);
    }
}
