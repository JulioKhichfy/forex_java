package com.jevforex.app.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Perfis prontos de risco ({@code risk.presets} no application.yml), aplicados com um clique na página Risco.
 * Cada perfil tem {@code label}, {@code description} e as partes de {@code trading.risk} que ele define:
 * {@code global}, {@code exits} e cada mercado de {@code markets} são substituídos inteiros; {@code orders} só nos
 * campos listados. O stop/alvo em US$ ({@code money}) e os perfis de perda extras não mudam.
 */
@ConfigurationProperties(prefix = "risk")
public record RiskPresetProperties(Map<String, Map<String, Object>> presets) {

    public RiskPresetProperties {
        presets = presets == null ? Map.of() : new LinkedHashMap<>(presets);
    }
}
