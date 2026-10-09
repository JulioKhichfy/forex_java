package com.jevforex.app.config;

import com.jevforex.features.FeatureConfig;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;
import java.util.Map;

/** Bloco {@code features} do application.yml (passos 3c e 4d). Ver {@link FeatureConfig} para o significado. */
@ConfigurationProperties(prefix = "features")
public record FeatureProperties(String fset, Integer entryDelayMinutes, List<Integer> horizonsMinutes,
                                Double labelThresholdAtr, Integer eventLagMinutes, List<Integer> controlHoursUtc,
                                Calendar calendar, Text text) {

    public record Calendar(Map<String, Double> importanceWeights, Integer decayMinutes, Integer sigmaWindow,
                           Integer sigmaMinHistory, List<String> negativePolarity) {
    }

    public record Text(Boolean enabled, String qset, String model, Double tauShortHours, Double tauLongHours,
                       Double minRelevance) {
    }

    /** Valores ausentes no yml ficam com os do documento mestre. */
    public FeatureConfig toConfig() {
        FeatureConfig d = FeatureConfig.defaults();
        Calendar c = calendar == null ? new Calendar(null, null, null, null, null) : calendar;
        FeatureConfig.Text dt = d.text();
        FeatureConfig.Text t = text != null && Boolean.FALSE.equals(text.enabled()) ? null : new FeatureConfig.Text(
                text == null || text.qset() == null ? dt.qset() : text.qset(),
                text == null || text.model() == null ? dt.model() : text.model(),
                text == null || text.tauShortHours() == null ? dt.tauShortHours() : text.tauShortHours(),
                text == null || text.tauLongHours() == null ? dt.tauLongHours() : text.tauLongHours(),
                text == null || text.minRelevance() == null ? dt.minRelevance() : text.minRelevance());
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
                c.negativePolarity() == null ? d.negativePolarity() : c.negativePolarity(),
                t);
    }
}
