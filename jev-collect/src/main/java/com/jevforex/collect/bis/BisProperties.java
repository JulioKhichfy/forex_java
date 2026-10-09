package com.jevforex.collect.bis;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Bloco {@code bis} do application.yml: acervo de discursos de banqueiros centrais do BIS
 * (uso não comercial permitido; documento mestre, capítulo 5).
 *
 * @param urlTemplate endereço do zip de um ano ({year} é substituído)
 * @param fromYear    primeiro ano baixado
 */
@ConfigurationProperties(prefix = "bis")
public record BisProperties(String urlTemplate, int fromYear) {

    public BisProperties {
        if (urlTemplate == null || urlTemplate.isBlank()) {
            urlTemplate = "https://www.bis.org/pages/download-central-bankers-speeches/speeches-{year}.zip";
        }
        if (fromYear <= 0) fromYear = 2021;
    }

    public String url(int year) {
        return urlTemplate.replace("{year}", String.valueOf(year));
    }
}
