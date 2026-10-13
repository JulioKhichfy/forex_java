package com.jevforex.app.live;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.Map;

/**
 * Bloco {@code live} do application.yml: previsões ao vivo dos modelos de produção (passo 6a).
 *
 * @param enabled            agenda as previsões no modo servidor (nos mesmos momentos do treino)
 * @param lookbackDays       dias de M1 lidos para as features. O regime compara o ATR com a mediana das últimas
 *                           1.920 barras M15: ~20 dias num mercado de 24 h, mas ~105 dias corridos numa ação
 *                           (6,5 h por dia). Janela curta demais = regime vazio = o símbolo fica sem previsão.
 * @param lookbackDaysByMarket a mesma janela por mercado (ex.: stocks: 120); ausente = lookbackDays
 * @param cron               quando conferir se é momento de decisão (padrão: segundo 20 de cada minuto, para a
 *                           barra que fecha no momento chegar do MT5)
 */
@ConfigurationProperties(prefix = "live")
public record LiveProperties(Boolean enabled, Integer lookbackDays, Map<String, Integer> lookbackDaysByMarket,
                             String cron) {

    public LiveProperties {
        if (enabled == null) enabled = false;
        if (lookbackDays == null) lookbackDays = 40;
        if (cron == null || cron.isBlank()) cron = "20 * * * * *";
        if (lookbackDays < 30) throw new IllegalArgumentException("live.lookback-days deve ser >= 30 (regime: 20 dias úteis)");
        lookbackDaysByMarket = lookbackDaysByMarket == null ? Map.of() : Map.copyOf(lookbackDaysByMarket);
    }

    public int lookbackFor(String market) {
        return lookbackDaysByMarket.getOrDefault(market, lookbackDays);
    }
}
