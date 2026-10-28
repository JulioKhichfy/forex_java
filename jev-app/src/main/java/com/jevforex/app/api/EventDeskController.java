package com.jevforex.app.api;

import com.jevforex.app.config.TradingProperties;
import com.jevforex.app.trade.EventDeskService;
import com.jevforex.app.trade.OrderService;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Página "Principal de hoje". Só em 127.0.0.1.
 *
 * <pre>
 * GET  /api/desk?back=6&ahead=48        eventos de importância alta (agrupados por moeda e horário), planos e ordens
 * POST /api/desk/plan                    {currency, at, eventCode, mode: MODEL|SURPRISE|NOW, symbols, lot, stopUsd,
 *                                         takeUsd, closeAfter, currencySide}
 * POST /api/desk/plan/{id}/cancel        desarma um plano automático
 * </pre>
 */
@RestController
@Profile("!cli")
@RequestMapping("/api/desk")
public class EventDeskController {

    private final EventDeskService desk;
    private final OrderService orders;
    private final TradingProperties trading;

    public EventDeskController(EventDeskService desk, OrderService orders, TradingProperties trading) {
        this.desk = desk;
        this.orders = orders;
        this.trading = trading;
    }

    @GetMapping
    public Map<String, Object> view(@RequestParam(defaultValue = "6") int back, @RequestParam(defaultValue = "48") int ahead) {
        Map<String, Object> out = desk.view(back, ahead);
        out.put("mode", trading.mode());
        out.put("blocked", orders.blocked());
        return out;
    }

    public record PlanRequest(String currency, Instant at, String eventCode, String mode, List<String> symbols, Double lot,
                              double stopUsd, double takeUsd, Integer closeAfter, Integer currencySide) {
    }

    @PostMapping("/plan")
    public ResponseEntity<Object> plan(@RequestBody PlanRequest r) {
        try {
            return ResponseEntity.ok(desk.createPlan(r.currency(), r.at(), r.eventCode(), r.mode(), r.symbols(), r.lot(),
                    r.stopUsd(), r.takeUsd(), r.closeAfter(), r.currencySide()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(409).body(Map.of("message", e.getMessage()));
        }
    }

    @PostMapping("/plan/{id}/cancel")
    public Map<String, Object> cancel(@PathVariable long id) {
        desk.cancel(id);
        return Map.of("cancelled", id);
    }
}
