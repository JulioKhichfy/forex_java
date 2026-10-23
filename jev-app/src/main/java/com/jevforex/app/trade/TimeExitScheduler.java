package com.jevforex.app.trade;

import com.jevforex.app.config.RiskSettingsHolder;
import com.jevforex.collect.mt5.Mt5Parsers;
import com.jevforex.core.Instrument;
import com.jevforex.core.risk.RiskSettings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Saída por tempo (capítulo 12): posição aberta por uma ordem com {@code close_after_utc} vencido recebe um pedido
 * de fechamento. Se o Java parar, a posição continua protegida pelo SL/TP no servidor da corretora.
 *
 * <p>Prorrogação com lucro, dinâmica (pedido do operador em 15/10/2026, {@code trading.risk.money.time-lock-*}):
 * ligada, a posição que chega ao fim do tempo COM lucro não fecha a mercado. Recebe um stop que garante
 * {@code keep-pct} do lucro e um alvo {@code extra-pct} além do preço. Depois, a cada 5 s: quando o lucro passa a
 * máxima anterior em {@code step-pct}, o stop sobe (garante a % da nova máxima), o alvo vai à frente e a janela de
 * {@code time-lock-minutes} renova. Sem nova máxima na janela, no teto ({@code time-lock-max-minutes}) ou se o EA
 * recusar o início, fecha a mercado. Sem lucro, sem cotação recente ou com EA anterior à 2.4: fecha como sempre
 * (falhar fechado).</p>
 */
@Component
@Profile("!cli")
public class TimeExitScheduler {

    private static final Logger log = LoggerFactory.getLogger(TimeExitScheduler.class);
    private static final Duration QUOTE_MAX_AGE = Duration.ofSeconds(20);
    private static final List<String> LOCK_FAILED = List.of("REJECTED", "EXPIRED", "CANCELLED");

    private final TradeRepository repo;
    private final OrderService orders;
    private final RiskSettingsHolder riskHolder;
    private final TrailingService trailing;
    private boolean oldEaWarned;

    public TimeExitScheduler(TradeRepository repo, OrderService orders, RiskSettingsHolder riskHolder,
                             TrailingService trailing) {
        this.repo = repo;
        this.orders = orders;
        this.riskHolder = riskHolder;
        this.trailing = trailing;
    }

    @Scheduled(initialDelayString = "PT30S", fixedDelayString = "PT5S")
    public synchronized void tick() {
        RiskSettings.MoneyExits m = riskHolder.get().money();
        follow(m);
        List<TradeRepository.DueExit> due = repo.dueTimeExits();
        java.util.Set<Long> casino = due.isEmpty() ? java.util.Set.of() : repo.casinoTickets();
        for (TradeRepository.DueExit d : due) {
            try {
                String why = null;
                if (d.lockUntil() != null) {
                    why = LOCK_FAILED.contains(d.lockStatus())
                            ? "a prorrogação não pegou (" + d.lockStatus() + ")" : "fim da prorrogação com lucro";
                } else if (m.timeLockEnabled() && !casino.contains(d.ticket())) {   // Cassino: fecha no tempo, sem prorrogar
                    why = start(d.ticket(), m);
                    if (why == null) continue;   // prorrogada (ou esperando um MODIFY do trailing terminar)
                }
                long id = orders.close(d.ticket(), why == null ? "saída por tempo" : "saída por tempo: " + why);
                log.info("Saída por tempo: posição {} → ordem de fechamento {}{}", d.ticket(), id,
                        why == null ? "" : " (" + why + ")");
            } catch (RuntimeException e) {
                log.warn("Saída por tempo da posição {} falhou: {}", d.ticket(), e.getMessage());
            }
        }
    }

    /** Fim do tempo com lucro: primeiro stop/alvo. null = prorrogou (ou espera); senão, o motivo de fechar. */
    private String start(long ticket, RiskSettings.MoneyExits m) {
        if (repo.pendingModify(ticket)) return null;   // um MODIFY do trailing em andamento: decide na próxima volta
        Object c = context(ticket);
        if (c instanceof String why) return why;
        Ctx x = (Ctx) c;
        Lock l = plan(x.pos.side(), x.pos.openPrice(), x.pos.sl(), x.bid, x.ask, x.perPrice, x.minDist, x.tick, m);
        if (l.sl() == null) return l.why();
        Instant now = Instant.now();
        Instant until = deadline(now, now, m);
        String text = describe("prorrogação com lucro", x, l, m);
        long id = orders.modify(x.pos, l.sl(), l.tp(), text);
        repo.markTimeLock(ticket, now, until, l.profitUsd(), id);
        log.info("Saída por tempo {} {} #{}: {} (ordem {})", x.pos.side(), x.pos.symbol(), ticket, text, id);
        return null;
    }

    /** Prorrogações em andamento: nova máxima de lucro → stop e alvo andam e a janela renova. */
    private void follow(RiskSettings.MoneyExits m) {
        for (TradeRepository.ActiveLock a : repo.activeTimeLocks()) {
            try {
                if (repo.pendingModify(a.ticket())) continue;
                if (!(context(a.ticket()) instanceof Ctx x)) continue;   // sem cotação agora: fica como está
                boolean buy = "BUY".equals(x.pos.side());
                double profit = (buy ? x.bid - x.pos.openPrice() : x.pos.openPrice() - x.ask) * x.perPrice;
                if (!isNewPeak(profit, a.peakUsd(), m)) continue;
                Lock l = plan(x.pos.side(), x.pos.openPrice(), x.pos.sl(), x.bid, x.ask, x.perPrice, x.minDist, x.tick, m);
                if (l.sl() == null) continue;
                Instant until = deadline(Instant.now(), a.startedAt(), m);
                String text = describe("prorrogação: nova máxima", x, l, m);
                long id = orders.modify(x.pos, l.sl(), l.tp(), text);
                repo.updateTimeLock(a.ticket(), until, l.profitUsd());
                log.info("Prorrogação {} {} #{}: {} (ordem {})", x.pos.side(), x.pos.symbol(), a.ticket(), text, id);
            } catch (RuntimeException e) {
                log.warn("Prorrogação da posição {} falhou: {}", a.ticket(), e.getMessage());
            }
        }
    }

    private String describe(String what, Ctx x, Lock l, RiskSettings.MoneyExits m) {
        boolean buy = "BUY".equals(x.pos.side());
        double kept = (buy ? l.sl() - x.pos.openPrice() : x.pos.openPrice() - l.sl()) * x.perPrice;
        double extra = (buy ? l.tp() - x.bid : x.ask - l.tp()) * x.perPrice;
        return String.format(Locale.ROOT, "%s: lucro US$ %.2f → stop %s garante US$ %.2f, alvo %s busca +US$ %.2f, "
                + "janela %d min", what, l.profitUsd(), l.sl(), kept, l.tp(), extra, m.timeLockMinutes());
    }

    /** Posição, cotação e valores do símbolo; ou o motivo de não mexer (String). */
    private Object context(long ticket) {
        Optional<TradeRepository.Account> acc = repo.latestAccount();
        if (acc.isEmpty() || acc.get().reportedAt() == null
                || acc.get().reportedAt().isBefore(Instant.now().minus(QUOTE_MAX_AGE))) {
            return "sem cotação recente do EA para prorrogar";
        }
        if (!trailing.eaAtLeast(acc.get().detailJson(), 2, 4)) {
            if (!oldEaWarned) log.warn("Prorrogação com lucro ligada, mas o EA conectado não sabe mover o alvo (precisa da "
                    + "versão 2.4: rode mql5\\install.ps1 e recarregue o JevExecutor). As posições fecham por tempo como antes.");
            oldEaWarned = true;
            return "EA anterior à 2.4 (não move o alvo)";
        }
        oldEaWarned = false;
        TradeRepository.Position pos = repo.ownPositions().stream().filter(p -> p.ticket() == ticket).findFirst().orElse(null);
        if (pos == null) return "posição não encontrada";
        double[] q = trailing.quotes(acc.get().detailJson()).get(pos.symbol());
        if (q == null) return "sem cotação de " + pos.symbol();
        Instrument inst = orders.instrument(pos.symbol());
        if (inst == null) return "sem especificação de " + pos.symbol();
        Mt5Parsers.InstrumentSpec sp = orders.spec(pos.symbol());
        double point = sp == null ? inst.tickSize() : sp.point();
        double minDist = (sp == null || sp.stopsLevel() == null ? 0 : sp.stopsLevel()) * point + point;
        return new Ctx(pos, q[0], q[1], inst.lossPerLot(1.0) * pos.volume(), minDist, inst.tickSize());
    }

    private record Ctx(TradeRepository.Position pos, double bid, double ask, double perPrice, double minDist, double tick) {
    }

    /** Stop e alvo da prorrogação (preços) ou, com {@code sl} null, o motivo de não prorrogar. */
    record Lock(Double sl, Double tp, double profitUsd, String why) {
    }

    /** Fecha a mercado neste instante: a janela a partir de agora, sem passar do teto contado do início. */
    static Instant deadline(Instant now, Instant startedAt, RiskSettings.MoneyExits m) {
        Instant window = now.plus(Duration.ofMinutes(m.timeLockMinutes()));
        Instant cap = startedAt.plus(Duration.ofMinutes(m.timeLockMaxMinutes()));
        return window.isBefore(cap) ? window : cap;
    }

    /** O lucro de agora é uma nova máxima: passa a anterior em pelo menos {@code step-pct}. */
    static boolean isNewPeak(double profitUsd, double peakUsd, RiskSettings.MoneyExits m) {
        return profitUsd > peakUsd && profitUsd >= peakUsd * (1 + m.timeLockStepPct() / 100);
    }

    /**
     * Novo stop e alvo para prorrogar uma posição com lucro (no início e a cada nova máxima).
     *
     * @param bid      o lado em que a COMPRA fecha
     * @param ask      o lado em que a VENDA fecha
     * @param perPrice US$ por 1,0 de preço com o volume da posição
     * @param minDist  distância mínima do stop/alvo ao preço (nível da corretora)
     * @param tick     passo de preço do símbolo
     */
    static Lock plan(String side, double open, Double currentSl, double bid, double ask, double perPrice,
                     double minDist, double tick, RiskSettings.MoneyExits m) {
        if (!(perPrice > 0) || !(tick > 0)) return new Lock(null, null, 0, "sem o valor do ponto do símbolo");
        boolean buy = "BUY".equals(side);
        double profit = (buy ? bid - open : open - ask) * perPrice;
        if (profit < m.timeLockMinProfitUsd()) {
            return new Lock(null, null, profit, String.format(Locale.ROOT, "lucro US$ %.2f abaixo de US$ %.2f para prorrogar",
                    profit, m.timeLockMinProfitUsd()));
        }
        double keep = profit * m.timeLockKeepPct() / 100, extra = profit * m.timeLockExtraPct() / 100;
        double sl = buy ? open + keep / perPrice : open - keep / perPrice;
        sl = buy ? Math.min(sl, bid - minDist) : Math.max(sl, ask + minDist);   // não colado no preço
        sl = buy ? Math.floor(sl / tick + 1e-9) * tick : Math.ceil(sl / tick - 1e-9) * tick;
        if (currentSl != null && currentSl > 0) {
            sl = buy ? Math.max(sl, currentSl) : Math.min(sl, currentSl);       // nunca pior que o atual
        }
        if (buy ? sl <= open : sl >= open) {
            return new Lock(null, null, profit, "lucro pequeno demais para um stop além da entrada (mínimo da corretora)");
        }
        double tp = buy ? bid + extra / perPrice : ask - extra / perPrice;
        tp = buy ? Math.max(tp, bid + minDist) : Math.min(tp, ask - minDist);
        tp = buy ? Math.ceil(tp / tick - 1e-9) * tick : Math.floor(tp / tick + 1e-9) * tick;
        return new Lock(onTick(sl, tick), onTick(tp, tick), profit, null);
    }

    /** No passo de preço, sem o ruído do ponto flutuante (4147.300000000001 → 4147.3). */
    private static double onTick(double price, double tick) {
        return java.math.BigDecimal.valueOf(Math.round(price / tick)).multiply(java.math.BigDecimal.valueOf(tick)).doubleValue();
    }
}
