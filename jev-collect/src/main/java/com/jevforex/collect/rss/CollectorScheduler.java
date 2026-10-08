package com.jevforex.collect.rss;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Roda a coleta periodicamente quando o app está em modo servidor e collect.enabled=true. */
@Component
@Profile("!cli")
@ConditionalOnProperty(prefix = "collect", name = "enabled", havingValue = "true")
public class CollectorScheduler {

    private final FeedCollector collector;

    public CollectorScheduler(FeedCollector collector) {
        this.collector = collector;
    }

    @Scheduled(initialDelayString = "PT5S", fixedDelayString = "${collect.interval-seconds:60}000")
    public void tick() {
        collector.runOnce();
    }
}
