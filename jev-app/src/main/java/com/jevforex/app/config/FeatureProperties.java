package com.jevforex.app.config;

import com.jevforex.features.FeatureConfig;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;
import java.util.Map;

/** Bloco {@code features} do application.yml (passo 3c). Ver {@link FeatureConfig} para o significado. */
@ConfigurationProperties(prefix = "features")
public record FeatureProperties(String fset, Integer entryDelayMinutes, List<Integer> horizonsMinutes,
                                Double labelThresholdAtr, Integer eventLagMinutes, List<Integer> controlHoursUtc,
                                Calendar calendar) {

    public record Calendar(Map<String, Double> importanceWeights, Integer decayMinutes, Integer sigmaWindow,
                           Integer sigmaMinHistory, List<String> negativePolarity) {
    }

    /** Valores ausentes no yml ficam com os do documento mestre. */
    public FeatureConfig toConfig() {
        FeatureConfig d = FeatureConfig.defaults();
        Calendar c = calendar == null ? new Calendar(null, null, null, null, null) : calendar;
        return new FeatureConfig(
                fset == null ? d.fset() : fset,
                entryDelayMinutes == null ? d.entryDelayMinutes() : entryDelayMinutes,
                horizonsMinutes == null ? d.horizonsMinutes() : horizonsMinutes,
                labelThresholdAtr == null ? d.labelThresholdAtr() : labelThresholdAtr,
                eventLagMinutes == null ? d.eventLagMinutes() : eventLagMinutes,
                controlHoursUtc == null ? d.controlHoursUtc() : controlHoursUtc,
                c.importanceWeights() == null ? d.importanceWeights() : c.importanceWeights(),
                c.decayMinutes() == null ? d.surpriseDecayMinutes() : c.decayMinutes(),
                c.sigmaWindow() == null ? d.sigmaWindow() : c.sigmaWindow(),
                c.sigmaMinHistory() == null ? d.sigmaMinHistory() : c.sigmaMinHistory(),
                c.negativePolarity() == null ? d.negativePolarity() : c.negativePolarity());
    }
}
