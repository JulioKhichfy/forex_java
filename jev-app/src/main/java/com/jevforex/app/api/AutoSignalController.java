package com.jevforex.app.api;

import com.jevforex.app.config.TradingProperties;
import com.jevforex.app.trade.AutoSignalService;
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
 * Sinal automático (página Hoje). Só em 127.0.0.1.
 *
 * <pre>
 * GET  /api/auto-signal                        estado de cada mercado permitido, a regra e os últimos sinais vistos
 * POST /api/auto-signal/start?market=metals[&lot=0.05]   liga (sem lot = lote mínimo)
 * POST /api/auto-signal/stop?market=metals     desliga
 * </pre>
 */
@RestController
@Profile("!cli")
@RequestMapping("/api/auto-signal")
public class AutoSignalController {

    private final AutoSignalService auto;
    private final OrderService orders;
    private final TradingProperties trading;

    public AutoSignalController(AutoSignalService auto, OrderService orders, TradingProperties trading) {
        this.auto = auto;
        this.orders = orders;
        this.trading = trading;
    }

    @GetMapping
    public Map<String, Object> state() {
        Map<String, Object> out = auto.state();
        out.put("mode", trading.mode());
        out.put("blocked", orders.blocked());
        return out;
    }

    @PostMapping("/start")
    public ResponseEntity<Object> start(@RequestParam String market, @RequestParam(required = false) Double lot) {
        try {
            return ResponseEntity.ok(auto.start(market, lot));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(409).body(Map.of("message", e.getMessage()));
        }
    }

    @PostMapping("/stop")
    public AutoSignalService.Config stop(@RequestParam String market) {
        return auto.stop(market, "desligado pelo operador");
    }
}
