package com.jevforex.collect.news;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * Bloco {@code news} do application.yml (passo 10a): manchetes do GDELT e documentos da SEC (EDGAR), além dos
 * bancos centrais. Só coleta para o bronze; o Jev entra depois, com plano e teto de gasto.
 */
@ConfigurationProperties(prefix = "news")
public record NewsProperties(Gdelt gdelt, Edgar edgar) {

    public NewsProperties {
        if (gdelt == null) gdelt = new Gdelt(false, null, null, null, null, null, null, null, null, null);
        if (edgar == null) edgar = new Edgar(false, null, null, null, null, null);
    }

    /**
     * GDELT GKG 2.1: um arquivo a cada 15 min (data.gdeltproject.org) com as notícias que o GDELT leu no período —
     * site, URL, título, temas, pessoas, organizações e tom. Download estático (a API de busca bloqueia o IP por
     * vários minutos com poucos pedidos). Fica no bronze só o que passa no filtro abaixo.
     *
     * @param parallel            downloads simultâneos no histórico
     * @param retries             novas tentativas por arquivo
     * @param liveMinutes         de quanto em quanto tempo a coleta ao vivo roda
     * @param availableLagMinutes no silver, a manchete vale a partir do horário do lote + isto (o GDELT publica o
     *                            arquivo alguns minutos depois; o normalize mostra o atraso real do ao vivo)
     * @param domains             sites aceitos (o resto é descartado)
     * @param alwaysDomains       sites guardados com qualquer tema (especializados: forex, ouro, cripto)
     * @param themePrefixes       nos demais sites, só notícias com algum tema do GDELT começando assim
     * @param titleKeywords       … ou com uma destas palavras no título (minúsculas)
     */
    public record Gdelt(Boolean enabled, String baseUrl, Integer parallel, Integer retries, Integer liveMinutes,
                        Integer availableLagMinutes, List<String> domains, List<String> alwaysDomains,
                        List<String> themePrefixes, List<String> titleKeywords) {
        public Gdelt {
            if (enabled == null) enabled = false;
            if (baseUrl == null || baseUrl.isBlank()) baseUrl = "http://data.gdeltproject.org/gdeltv2/";
            if (!baseUrl.endsWith("/")) baseUrl += "/";
            if (parallel == null || parallel < 1) parallel = 3;
            if (retries == null) retries = 3;
            if (liveMinutes == null) liveMinutes = 15;
            if (availableLagMinutes == null) availableLagMinutes = 15;
            domains = domains == null ? List.of() : List.copyOf(domains);
            alwaysDomains = alwaysDomains == null ? List.of() : List.copyOf(alwaysDomains);
            themePrefixes = themePrefixes == null ? List.of() : List.copyOf(themePrefixes);
            titleKeywords = titleKeywords == null ? List.of() : titleKeywords.stream().map(String::toLowerCase).toList();
        }
    }

    /**
     * SEC EDGAR: comunicados (8-K: resultados, fatos relevantes) e relatórios (10-Q, 10-K) das ações em uso.
     *
     * @param userAgent   a SEC exige nome e e-mail de contato ("Nome email@dominio"); vazio = não coleta
     * @param delayMillis pausa entre pedidos (limite da SEC: 10 por segundo)
     * @param forms       tipos de documento guardados
     * @param from        backfill a partir desta data de entrega
     * @param companies   símbolo do MT5 → CIK da SEC
     */
    public record Edgar(Boolean enabled, String userAgent, Integer delayMillis, List<String> forms, LocalDate from,
                        Map<String, Long> companies) {
        public Edgar {
            if (enabled == null) enabled = false;
            if (delayMillis == null) delayMillis = 250;
            forms = forms == null || forms.isEmpty() ? List.of("8-K", "8-K/A", "10-Q", "10-K", "6-K") : List.copyOf(forms);
            if (from == null) from = LocalDate.of(2021, 10, 1);
            companies = companies == null ? Map.of() : Map.copyOf(companies);
        }

        public boolean configured() {
            return userAgent != null && userAgent.contains("@");
        }
    }
}
