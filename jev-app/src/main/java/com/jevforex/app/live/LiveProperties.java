package com.jevforex.app.live;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Bloco {@code live} do application.yml: previsões ao vivo dos modelos de produção (passo 6a).
 *
 * @param enabled       agenda as previsões no modo servidor (nos mesmos momentos do treino)
 * @param lookbackDays  dias de M1 lidos para as features (regime e spread típico pedem ~28)
 * @param cron          quando conferir se é momento de decisão (padrão: segundo 20 de cada minuto, para a barra
 *                      que fecha no momento chegar do MT5)
 */
@ConfigurationProperties(prefix = "live")
public record LiveProperties(Boolean enabled, Integer lookbackDays, String cron) {

    public LiveProperties {
        if (enabled == null) enabled = false;
        if (lookbackDays == null) lookbackDays = 40;
        if (cron == null || cron.isBlank()) cron = "20 * * * * *";
        if (lookbackDays < 30) throw new IllegalArgumentException("live.lookback-days deve ser >= 30 (regime: 20 dias úteis)");
    }
}
