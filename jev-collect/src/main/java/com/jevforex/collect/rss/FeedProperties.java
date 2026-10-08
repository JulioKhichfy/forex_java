package com.jevforex.collect.rss;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * Bloco {@code collect} do application.yml.
 *
 * @param enabled              liga o agendamento automático (modo servidor)
 * @param intervalSeconds      intervalo entre rodadas de coleta
 * @param userAgent            identificação educada do coletor (inclua um contato)
 * @param downloadItemPages    baixar também a página HTML de cada item (necessário para o texto)
 * @param politeDelayMillis    pausa entre requisições ao mesmo site
 * @param feeds                lista de feeds
 */
@ConfigurationProperties(prefix = "collect")
public record FeedProperties(
        boolean enabled,
        int intervalSeconds,
        String userAgent,
        boolean downloadItemPages,
        long politeDelayMillis,
        List<Feed> feeds) {

    public FeedProperties {
        if (intervalSeconds <= 0) intervalSeconds = 60;
        if (userAgent == null || userAgent.isBlank()) userAgent = "jev-forex-collector/0.1";
        if (politeDelayMillis <= 0) politeDelayMillis = 1000;
        feeds = feeds == null ? List.of() : List.copyOf(feeds);
    }

    /**
     * @param id       identificador curto (ex.: fed-speeches)
     * @param issuer   instituição (ex.: Federal Reserve)
     * @param currency moeda afetada (ex.: USD)
     * @param market   fx | metals | crypto
     * @param docType  tipo de documento (cb_text, headline)
     * @param url      endereço do RSS/Atom
     * @param enabled  liga/desliga este feed
     */
    public record Feed(String id, String issuer, String currency, String market, String docType, String url,
                       boolean enabled) {
        public Feed {
            if (market == null || market.isBlank()) market = "fx";
            if (docType == null || docType.isBlank()) docType = "cb_text";
        }
    }
}
