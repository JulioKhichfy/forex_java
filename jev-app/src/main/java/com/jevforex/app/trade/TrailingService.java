package com.jevforex.app.trade;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jevforex.app.config.RiskSettingsHolder;
import com.jevforex.collect.mt5.Mt5Parsers;
import com.jevforex.core.Instrument;
import com.jevforex.core.risk.RiskSettings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Trailing stop (página "Stop em US$", pedido do operador em 14/10/2026): com lucro de {@code trail-start-usd}, o stop
 * da posição vai para o zero a zero; depois acompanha o preço, travando lucro − {@code trail-distance-usd}. O stop
 * só anda a favor, de {@code trail-step-usd} em {@code trail-step-usd} no mínimo, e quem o move é o EA (pedido MODIFY);
 * o alvo fica onde está.
 *
 * <p>Falhar fechado: sem heartbeat recente do EA (cotação velha) ou sem cotação do símbolo, não mexe.</p>
 */
@Service
@Profile("!cli")
public class TrailingService {

    private static final Logger log = LoggerFactory.getLogger(TrailingService.class);
    private static final Duration QUOTE_MAX_AGE = Duration.ofSeconds(20);

    private final TradeRepository repo;
    private final OrderService orders;
    private final RiskSettingsHolder riskHolder;
    private final ObjectMapper mapper;
    private boolean oldEaWarned;

    public TrailingService(TradeRepository repo, OrderService orders, RiskSettingsHolder riskHolder, ObjectMapper mapper) {
        this.repo = repo;
        this.orders = orders;
        this.riskHolder = riskHolder;
        this.mapper = mapper;
    }

    @Scheduled(initialDelayString = "PT45S", fixedDelayString = "PT5S")
    public synchronized void tick() {
        RiskSettings.MoneyExits m = riskHolder.get().money();
        if (!m.trailEnabled()) return;
        Optional<TradeRepository.Account> acc = repo.latestAccount();
        if (acc.isEmpty() || acc.get().reportedAt() == null
                || acc.get().reportedAt().isBefore(Instant.now().minus(QUOTE_MAX_AGE))) {
            return;
        }
        if (!eaSupportsModify(acc.get().detailJson())) {
            if (!oldEaWarned) log.warn("Trailing ligado, mas o EA conectado não sabe mover o stop (precisa da versão 2.2: "
                    + "rode mql5\\install.ps1 e recarregue o JevExecutor no gráfico). Nenhum stop será movido.");
            oldEaWarned = true;
            return;
        }
        oldEaWarned = false;
        Map<String, double[]> quotes = quotes(acc.get().detailJson());
        for (TradeRepository.Position p : repo.positions()) {
            try {
                trail(p, quotes.get(p.symbol()), m);
            } catch (RuntimeException e) {
                log.warn("Trailing da posição {} falhou: {}", p.ticket(), e.toString());
            }
        }
    }

    private void trail(TradeRepository.Position p, double[] q, RiskSettings.MoneyExits m) {
        if (q == null || repo.pendingModify(p.ticket())) return;
        Instrument inst = orders.instrument(p.symbol());
        if (inst == null) return;
        Mt5Parsers.InstrumentSpec sp = orders.spec(p.symbol());
        double point = sp == null ? inst.tickSize() : sp.point();
        double minDist = (sp == null || sp.stopsLevel() == null ? 0 : sp.stopsLevel()) * point + point;
        double perPrice = inst.lossPerLot(1.0) * p.volume();
        Double sl = newStop(p.side(), p.openPrice(), p.sl(), q[0], q[1], perPrice, minDist, inst.tickSize(), m);
        if (sl == null) return;
        double locked = ("BUY".equals(p.side()) ? sl - p.openPrice() : p.openPrice() - sl) * perPrice;
        String why = String.format(Locale.ROOT, "trailing: stop a %s trava US$ %.2f", sl, locked);
        long id = orders.modifyStop(p, sl, why);
        log.info("Trailing {} {} #{}: {} (ordem {})", p.side(), p.symbol(), p.ticket(), why, id);
    }

    /**
     * O novo stop (preço) ou null = deixar como está.
     *
     * @param bid       o lado em que a COMPRA fecha
     * @param ask       o lado em que a VENDA fecha
     * @param perPrice  US$ por 1,0 de preço com o volume da posição
     * @param minDist   distância mínima do stop ao preço (nível da corretora)
     * @param tick      passo de preço do símbolo
     */
    static Double newStop(String side, double open, Double currentSl, double bid, double ask, double perPrice,
                          double minDist, double tick, RiskSettings.MoneyExits m) {
        if (!(perPrice > 0)) return null;
        boolean buy = "BUY".equals(side);
        double profit = (buy ? bid - open : open - ask) * perPrice;
        Double lock = m.lockedProfit(profit);
        if (lock == null) return null;
        double sl = buy ? open + lock / perPrice : open - lock / perPrice;
        // a corretora não aceita stop colado no preço: no máximo até a distância mínima
        sl = buy ? Math.min(sl, bid - minDist) : Math.max(sl, ask + minDist);
        // no passo de preço, arredondando para o lado que protege menos (nunca além do calculado)
        sl = buy ? Math.floor(sl / tick + 1e-9) * tick : Math.ceil(sl / tick - 1e-9) * tick;
        sl = Math.round(sl / tick) * tick;
        if (currentSl != null && currentSl > 0) {
            double gain = (buy ? sl - currentSl : currentSl - sl) * perPrice;
            if (gain < Math.max(m.trailStepUsd(), 1e-9)) return null;   // só anda a favor
        }
        return sl;
    }

    /** O EA reporta "JevExecutor/2.2" no heartbeat; MODIFY existe a partir da 2.2. */
    boolean eaSupportsModify(String detailJson) {
        try {
            String v = mapper.readTree(detailJson == null ? "{}" : detailJson).path("ea_version").asText("");
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("/(\\d+)\\.(\\d+)").matcher(v);
            if (!m.find()) return false;
            int major = Integer.parseInt(m.group(1)), minor = Integer.parseInt(m.group(2));
            return major > 2 || (major == 2 && minor >= 2);
        } catch (Exception e) {
            return false;
        }
    }

    /** symbol → {bid, ask} do último heartbeat do EA. */
    private Map<String, double[]> quotes(String detailJson) {
        Map<String, double[]> out = new HashMap<>();
        try {
            JsonNode d = mapper.readTree(detailJson == null ? "{}" : detailJson);
            for (JsonNode q : d.path("quotes")) {
                double bid = q.path("bid").asDouble(0), ask = q.path("ask").asDouble(0);
                if (bid > 0 && ask > 0) out.put(q.path("symbol").asText(), new double[]{bid, ask});
            }
        } catch (Exception e) {
            log.warn("Trailing: heartbeat ilegível: {}", e.getMessage());
        }
        return out;
    }
}
