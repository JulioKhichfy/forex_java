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
 * @param attachments          PDFs anexos no corpo das páginas
 * @param feeds                lista de feeds
 */
@ConfigurationProperties(prefix = "collect")
public record FeedProperties(
        boolean enabled,
        int intervalSeconds,
        String userAgent,
        boolean downloadItemPages,
        long politeDelayMillis,
        Attachments attachments,
        List<Feed> feeds) {

    public FeedProperties {
        if (intervalSeconds <= 0) intervalSeconds = 60;
        if (userAgent == null || userAgent.isBlank()) userAgent = "jev-forex-collector/0.1";
        if (politeDelayMillis <= 0) politeDelayMillis = 1000;
        if (attachments == null) attachments = new Attachments(false, 3, 20, 10);
        feeds = feeds == null ? List.of() : List.copyOf(feeds);
    }

    /**
     * @param enabled        baixa os PDFs ligados no corpo de cada página nova
     * @param maxPerItem     no máximo quantos PDFs por página
     * @param maxMb          PDF maior que isto é ignorado
     * @param backfillPerRun páginas antigas (sem anexos procurados) revisitadas por rodada
     */
    public record Attachments(boolean enabled, int maxPerItem, int maxMb, int backfillPerRun) {
        public Attachments {
            if (maxPerItem <= 0) maxPerItem = 3;
            if (maxMb <= 0) maxMb = 20;
            if (backfillPerRun < 0) backfillPerRun = 0;
        }
    }

    /**
     * @param id       identificador curto (ex.: fed-speeches)
     * @param issuer   instituição (ex.: Federal Reserve)
     * @param currency moeda afetada (ex.: USD)
     * @param market   fx | metals | crypto
     * @param docType  tipo de documento (cb_text, headline)
     * @param url         endereço do RSS/Atom
     * @param enabled     liga/desliga este feed
     * @param delayMillis pausa entre requisições a este site (0 = collect.polite-delay-millis)
     */
    public record Feed(String id, String issuer, String currency, String market, String docType, String url,
                       boolean enabled, long delayMillis) {
        public Feed {
            if (market == null || market.isBlank()) market = "fx";
            if (docType == null || docType.isBlank()) docType = "cb_text";
            if (delayMillis < 0) delayMillis = 0;
        }
    }

    public long delayFor(Feed feed) {
        return feed.delayMillis() > 0 ? feed.delayMillis() : politeDelayMillis;
    }
}
