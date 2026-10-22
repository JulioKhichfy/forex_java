package com.jevforex.app.api;

import com.jevforex.app.config.TradingProperties;
import com.jevforex.app.trade.HighFrequencyService;
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
 * Alta frequência (página Alta frequência). Só em 127.0.0.1.
 *
 * <pre>
 * GET  /api/hf?days=7                       estado de cada mercado, contagens, resultado real e últimas entradas
 * POST /api/hf/start?market=metals&model=B&horizon=15[&lot=0.2]&takeUsd=0.1&stopAtr=3
 * POST /api/hf/stop[?market=metals]         para um mercado (sem market = todos)
 * </pre>
 */
@RestController
@Profile("!cli")
@RequestMapping("/api/hf")
public class HighFrequencyController {

    private final HighFrequencyService hf;
    private final OrderService orders;
    private final TradingProperties trading;

    public HighFrequencyController(HighFrequencyService hf, OrderService orders, TradingProperties trading) {
        this.hf = hf;
        this.orders = orders;
        this.trading = trading;
    }

    @GetMapping
    public Map<String, Object> state(@RequestParam(defaultValue = "7") int days) {
        Map<String, Object> out = hf.state(days);
        out.put("mode", trading.mode());
        out.put("blocked", orders.blocked());
        out.put("loss_limits", orders.lossProfile());
        return out;
    }

    @PostMapping("/start")
    public ResponseEntity<Object> start(@RequestParam String market, @RequestParam(defaultValue = "B") String model,
                                        @RequestParam(defaultValue = "15") int horizon,
                                        @RequestParam(required = false) Double lot,
                                        @RequestParam(defaultValue = "0.10") double takeUsd,
                                        @RequestParam(defaultValue = "3.0") double stopAtr) {
        try {
            return ResponseEntity.ok(hf.start(market, model, horizon, lot, takeUsd, stopAtr));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(409).body(Map.of("message", e.getMessage()));
        }
    }

    @PostMapping("/stop")
    public Map<String, Object> stop(@RequestParam(required = false) String market) {
        if (market == null || market.isBlank()) hf.stopAll("parada pelo operador (todas)");
        else hf.stop(market, "parada pelo operador");
        return Map.of("stopped", market == null || market.isBlank() ? "todas" : market);
    }
}
