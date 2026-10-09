package com.jevforex.lake;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Bloco {@code lake} do application.yml.
 *
 * <p>O padrão fica FORA do repositório: dentro dele, a pasta jev-lake é o código do módulo.</p>
 */
@ConfigurationProperties(prefix = "lake")
public record LakeProperties(String root) {
    public LakeProperties {
        if (root == null || root.isBlank()) root = System.getProperty("user.home") + "/FOREX_JEV/jev-lake";
    }
}
