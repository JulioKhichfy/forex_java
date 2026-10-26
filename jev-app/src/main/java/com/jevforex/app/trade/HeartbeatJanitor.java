package com.jevforex.app.trade;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Limpeza dos heartbeats antigos (26/10/2026): o EA manda ~30 KB a cada 5 s (cotações, posições, fechamentos), ~0,5 GB
 * por dia, e só o mais recente é lido por inteiro. Depois de {@code mt5.heartbeat-detail-days} dias, o detalhe em JSON
 * vira {} — saldo, patrimônio, conta e horário continuam (base da perda do dia/semana e da conta conectada). Posições e
 * fechamentos ficam nas próprias tabelas (ea_position, ea_deal), não dependem disto.
 */
@Service
@Profile("!cli")
public class HeartbeatJanitor {

    private static final Logger log = LoggerFactory.getLogger(HeartbeatJanitor.class);
    private static final int BATCH = 2000;

    private final ObjectProvider<JdbcClient> jdbc;
    private final int keepDays;

    public HeartbeatJanitor(ObjectProvider<JdbcClient> jdbc, @Value("${mt5.heartbeat-detail-days:2}") int keepDays) {
        this.jdbc = jdbc;
        this.keepDays = Math.max(1, keepDays);
    }

    @Scheduled(initialDelayString = "PT3M", fixedDelayString = "PT6H")
    public void slimOld() {
        long total = 0, t0 = System.currentTimeMillis();
        try {
            while (true) {
                int n = jdbc.getObject().sql("""
                                UPDATE heartbeat SET detail = '{}'::jsonb
                                 WHERE id IN (SELECT id FROM heartbeat
                                               WHERE reported_at < now() - make_interval(days => :d) AND detail <> '{}'::jsonb
                                               LIMIT :b)
                                """).param("d", keepDays).param("b", BATCH).update();
                total += n;
                if (n < BATCH) break;
                Thread.sleep(200);   // em lotes, para não disputar o banco com as ordens
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (RuntimeException e) {
            log.warn("Limpeza dos heartbeats antigos falhou: {}", e.toString());
        }
        if (total > 0) {
            log.info("Heartbeats: detalhe de {} registro(s) com mais de {} dia(s) esvaziado ({} s)", total, keepDays,
                    (System.currentTimeMillis() - t0) / 1000);
        }
    }
}
