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
                                   Integer threads, Long seed, List<String> models, List<String> markets,
                                   java.util.Map<String, LocalDate> marketFrom) {

    /** Mercados com modelo (treino mensal e previsões ao vivo); o padrão é só fx. */
    public List<String> marketList() {
        return markets == null || markets.isEmpty() ? List.of("fx") : markets;
    }

    /** O experimento de um mercado: o início do período pode ser outro (índices e ações têm histórico mais curto). */
    public ExperimentConfig toConfig(String fset, RiskSettings.Exits riskExits, String market) {
        ExperimentConfig c = toConfig(fset, riskExits);
        if (market == null || "fx".equals(market)) return c;
        return c.withMarket(market, marketFrom == null ? null : marketFrom.get(market));
    }

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
                models == null ? d.models() : models,
                "fx");
    }
}
