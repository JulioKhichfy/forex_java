package com.jevforex.app.api;

import com.jevforex.app.config.RiskSettingsHolder;

import com.jevforex.app.config.TradingProperties;
import com.jevforex.app.trade.OrderService;
import com.jevforex.app.trade.TradeRepository;
import com.jevforex.core.risk.RiskSettings;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Operação pelo dashboard (passo 6c). Só em 127.0.0.1.
 *
 * <pre>
 * GET  /api/trade/state            conta, posições, ordens recentes, lote, BLOCK/FLATTEN e os limites em vigor
 * POST /api/trade/preview          {symbol, side, lot?} → travas, stop/alvo e risco em US$, sem enviar nada
 * POST /api/trade/orders           {symbol, side, lot?} → confere de novo e grava para o EA (ou recusa)
 * POST /api/trade/close/{ticket}   fecha uma posição
 * POST /api/trade/flatten          fecha tudo e liga o BLOCK
 * POST /api/trade/block?on=true    liga/desliga entradas novas
 * POST /api/trade/lot?value=0.01   lote do tíquete (até trading.risk.orders.max-lot)
 * POST /api/trade/loss-profile?name=conservador   perfil de perda em vigor (trading.risk.loss-profiles)
 * </pre>
 */
@RestController
@Profile("!cli")
@RequestMapping("/api/trade")
public class TradeController {

    private final OrderService orders;
    private final TradeRepository repo;
    private final RiskSettingsHolder riskHolder;
    private final TradingProperties trading;

    public TradeController(OrderService orders, TradeRepository repo, RiskSettingsHolder riskHolder, TradingProperties trading) {
        this.orders = orders;
        this.repo = repo;
        this.riskHolder = riskHolder;
        this.trading = trading;
    }

    public record OrderRequest(String symbol, String side, Double lot) {
    }

    @GetMapping("/state")
    public Map<String, Object> state() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("mode", trading.mode());
        out.put("account", repo.latestAccount().orElse(null));
        out.put("positions", repo.positions());
        out.put("orders", repo.recentOrders(30));
        out.put("lot", orders.lot());
        out.put("blocked", orders.blocked());
        out.put("flattening", orders.flattening());
        out.put("loss_profile", orders.lossProfileName());
        out.put("loss_profiles", riskHolder.get().lossProfiles());
        java.util.Map<String, java.util.List<String>> byMarket = orders.symbolsByMarket();
        out.put("symbols", byMarket.getOrDefault("fx", java.util.List.of()));
        out.put("symbols_by_market", byMarket);
        Map<String, Object> limits = new LinkedHashMap<>();
        limits.put("global", riskHolder.get().global());
        limits.put("exits", riskHolder.get().exits());
        limits.put("orders", riskHolder.get().orders());
        out.put("limits", limits);
        return out;
    }

    /**
     * Placar das SUAS operações (fechadas): cada uma com resultado em US$ e em R (resultado ÷ perda máxima
     * planejada na entrada), e o resumo do período.
     */
    @GetMapping("/performance")
    public Map<String, Object> performance(@RequestParam(defaultValue = "30") int days) {
        List<TradeRepository.ClosedTrade> list = repo.closedTrades(java.time.Instant.now()
                .minus(java.time.Duration.ofDays(Math.min(days, 3650))), 500);
        double gains = 0, losses = 0, sumR = 0;
        int wins = 0, withR = 0;
        for (TradeRepository.ClosedTrade t : list) {
            if (t.resultUsd() > 0) {
                wins++;
                gains += t.resultUsd();
            } else {
                losses -= t.resultUsd();
            }
            if (t.r() != null) {
                sumR += t.r();
                withR++;
            }
        }
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("trades", list.size());
        summary.put("wins", wins);
        summary.put("result_usd", gains - losses);
        summary.put("profit_factor", losses == 0 ? null : gains / losses);
        summary.put("avg_r", withR == 0 ? null : sumR / withR);
        summary.put("hit_rate", list.isEmpty() ? null : (double) wins / list.size());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("days", days);
        out.put("summary", summary);
        out.put("trades", list);
        return out;
    }

    @PostMapping("/preview")
    public OrderService.Preview preview(@RequestBody OrderRequest r) {
        return orders.preview(r.symbol(), r.side(), r.lot());
    }

    @PostMapping("/orders")
    public ResponseEntity<OrderService.Submit> submit(@RequestBody OrderRequest r) {
        OrderService.Submit s = orders.submit(r.symbol(), r.side(), r.lot());
        return s.orderId() == null ? ResponseEntity.status(409).body(s) : ResponseEntity.ok(s);
    }

    @PostMapping("/close/{ticket}")
    public Map<String, Object> close(@PathVariable long ticket) {
        return Map.of("orderId", orders.close(ticket, "fechada pelo dashboard"));
    }

    @PostMapping("/flatten")
    public Map<String, Object> flatten() {
        orders.flatten();
        return Map.of("flattening", true, "blocked", true);
    }

    @PostMapping("/block")
    public Map<String, Object> block(@RequestParam boolean on) {
        orders.block(on);
        return Map.of("blocked", on);
    }

    @PostMapping("/loss-profile")
    public Map<String, Object> lossProfile(@RequestParam String name) {
        return Map.of("loss_profile", name, "limits", orders.setLossProfile(name));
    }

    @PostMapping("/lot")
    public Map<String, Object> lot(@RequestParam double value) {
        return Map.of("lot", orders.setLot(value));
    }
}
