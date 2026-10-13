package com.jevforex.app.ops;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Normalize automático (live.normalize-cron). O MT5 grava um arquivo no bronze por barra de cada símbolo, e cada
 * previsão ao vivo lê o bronze que chegou depois do último normalize: sem reconstruir o silver, a leitura cresce
 * o dia inteiro (9 mil arquivos num dia) e a rodada passa a demorar minutos. Roda a mesma tarefa "normalizar" do
 * dashboard, num processo separado, fora das horas de controle.
 */
@Component
@Profile("!cli")
public class NormalizeScheduler {

    private static final Logger log = LoggerFactory.getLogger(NormalizeScheduler.class);

    private final JobService jobs;

    public NormalizeScheduler(JobService jobs) {
        this.jobs = jobs;
    }

    @Scheduled(cron = "${live.normalize-cron:-}", zone = "UTC")
    public void run() {
        try {
            jobs.start("normalizar");
            log.info("Normalize automático iniciado (tarefa normalizar)");
        } catch (IllegalStateException e) {
            // outra tarefa (ex.: treinamento mensal, que já normaliza) está rodando: fica para a próxima noite
            log.warn("Normalize automático pulado: {}", e.getMessage());
        }
    }
}
