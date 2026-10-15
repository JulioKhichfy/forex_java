package com.jevforex.collect.news;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Coleta ao vivo no modo servidor (passo 10a): GDELT a cada {@code news.gdelt.live-minutes} e EDGAR a cada 5 min,
 * cada um só se estiver ligado (e o EDGAR, com o contato da SEC configurado).
 */
@Component
@Profile("!cli")
public class NewsScheduler {

    private static final Logger log = LoggerFactory.getLogger(NewsScheduler.class);

    private final NewsProperties props;
    private final GdeltCollector gdelt;
    private final EdgarCollector edgar;
    private boolean edgarWarned;

    public NewsScheduler(NewsProperties props, GdeltCollector gdelt, EdgarCollector edgar) {
        this.props = props;
        this.gdelt = gdelt;
        this.edgar = edgar;
    }

    @Scheduled(initialDelayString = "PT2M", fixedDelayString = "#{${news.gdelt.live-minutes:15} * 60000}")
    public void gdelt() {
        if (!props.gdelt().enabled()) return;
        try {
            GdeltCollector.Report r = gdelt.collectRecent();
            log.info("GDELT ao vivo: {} lote(s), {} de {} notícias guardadas{}", r.batches(), r.rowsKept(), r.rowsTotal(),
                    r.failed() > 0 ? ", " + r.failed() + " com erro" : "");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (RuntimeException e) {
            log.warn("GDELT ao vivo falhou: {}", e.toString());
        }
    }

    @Scheduled(initialDelayString = "PT3M", fixedDelayString = "PT5M")
    public void edgar() {
        if (!props.edgar().enabled()) return;
        if (!props.edgar().configured()) {
            if (!edgarWarned) log.warn("EDGAR ligado mas sem contato da SEC (EDGAR_USER_AGENT): não coleta");
            edgarWarned = true;
            return;
        }
        try {
            EdgarCollector.Report r = edgar.collect(false);
            if (r.fetched() > 0 || r.failed() > 0) {
                log.info("EDGAR: {} documentos novos{}", r.fetched(), r.failed() > 0 ? ", " + r.failed() + " falhas" : "");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (RuntimeException e) {
            log.warn("EDGAR falhou: {}", e.toString());
        }
    }
}
