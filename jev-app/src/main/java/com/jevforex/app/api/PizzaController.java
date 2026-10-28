package com.jevforex.app.api;

import com.jevforex.app.config.TradingProperties;
import com.jevforex.app.trade.OrderService;
import com.jevforex.app.trade.PizzaService;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Compra Pizza (página Compra Pizza). Só em 127.0.0.1.
 *
 * <pre>
 * GET  /api/pizza                              partida atual (ou a última), entradas, candidatas de agora, histórico
 * POST /api/pizza/start?pizzaUsd=0.5&stopUsd=0.5   JOGAR
 * POST /api/pizza/stop                         desistir: fecha as posições da partida
 * </pre>
 */
@RestController
@Profile("!cli")
@RequestMapping("/api/pizza")
public class PizzaController {

    private final PizzaService pizza;
    private final OrderService orders;
    private final TradingProperties trading;

    public PizzaController(PizzaService pizza, OrderService orders, TradingProperties trading) {
        this.pizza = pizza;
        this.orders = orders;
        this.trading = trading;
    }

    @GetMapping
    public Map<String, Object> view() {
        Map<String, Object> out = pizza.view();
        out.put("mode", trading.mode());
        out.put("blocked", orders.blocked());
        out.put("loss_limits", orders.lossProfile());
        return out;
    }

    @PostMapping("/start")
    public ResponseEntity<Object> start(@RequestParam double pizzaUsd, @RequestParam double stopUsd) {
        try {
            return ResponseEntity.ok(pizza.start(pizzaUsd, stopUsd));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(409).body(Map.of("message", e.getMessage()));
        }
    }

    @PostMapping("/stop")
    public Map<String, Object> stop() {
        pizza.stop();
        return Map.of("ok", true);
    }
}
