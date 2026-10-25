package com.jevforex.app.api;

import com.jevforex.app.config.TradingProperties;
import com.jevforex.app.trade.MomentumService;
import com.jevforex.app.trade.OrderService;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Momentum BTC (página Hoje). Só em 127.0.0.1.
 *
 * <pre>
 * GET  /api/momentum                    estado, a regra, as últimas horas de sinal e o resultado de 30 dias
 * POST /api/momentum/start[?lot=0.01]   liga (sem lot = lote mínimo)
 * POST /api/momentum/stop               desliga
 * </pre>
 */
@RestController
@Profile("!cli")
@RequestMapping("/api/momentum")
public class MomentumController {

    private final MomentumService momentum;
    private final OrderService orders;
    private final TradingProperties trading;

    public MomentumController(MomentumService momentum, OrderService orders, TradingProperties trading) {
        this.momentum = momentum;
        this.orders = orders;
        this.trading = trading;
    }

    @GetMapping
    public Map<String, Object> state() {
        Map<String, Object> out = momentum.state();
        out.put("mode", trading.mode());
        out.put("blocked", orders.blocked());
        return out;
    }

    @PostMapping("/start")
    public ResponseEntity<Object> start(@RequestParam(required = false) Double lot) {
        try {
            return ResponseEntity.ok(momentum.start(lot));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(409).body(Map.of("message", e.getMessage()));
        }
    }

    @PostMapping("/stop")
    public MomentumService.Config stop() {
        return momentum.stop("desligado pelo operador");
    }
}
