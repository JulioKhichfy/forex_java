package com.jevforex.collect.mt5;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Varre o inbox do MT5 periodicamente quando o app está em modo servidor e mt5.import-enabled=true. */
@Component
@Profile("!cli")
@ConditionalOnProperty(prefix = "mt5", name = "import-enabled", havingValue = "true")
public class Mt5ImportScheduler {

    private final Mt5Importer importer;

    public Mt5ImportScheduler(Mt5Importer importer) {
        this.importer = importer;
    }

    @Scheduled(initialDelayString = "PT3S", fixedDelayString = "${mt5.import-interval-seconds:2}000")
    public void tick() {
        importer.importOnce();
    }
}
