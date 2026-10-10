package com.jevforex.app.trade;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Saída por tempo (capítulo 12): posição aberta por uma ordem com {@code close_after_utc} vencido recebe um pedido
 * de fechamento. Se o Java parar, a posição continua protegida pelo SL/TP no servidor da corretora.
 */
@Component
@Profile("!cli")
public class TimeExitScheduler {

    private static final Logger log = LoggerFactory.getLogger(TimeExitScheduler.class);

    private final TradeRepository repo;
    private final OrderService orders;

    public TimeExitScheduler(TradeRepository repo, OrderService orders) {
        this.repo = repo;
        this.orders = orders;
    }

    @Scheduled(initialDelayString = "PT30S", fixedDelayString = "PT15S")
    public void tick() {
        for (long ticket : repo.dueTimeExits()) {
            try {
                long id = orders.close(ticket, "saída por tempo");
                log.info("Saída por tempo: posição {} → ordem de fechamento {}", ticket, id);
            } catch (RuntimeException e) {
                log.warn("Saída por tempo da posição {} falhou: {}", ticket, e.getMessage());
            }
        }
    }
}
