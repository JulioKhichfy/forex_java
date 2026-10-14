package com.jevforex.app.api;

import com.jevforex.app.config.TradingProperties;
import com.jevforex.app.trade.OrderService;
import com.jevforex.app.trade.RouletteService;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Roleta (página Roleta russa). Só em 127.0.0.1.
 *
 * <pre>
 * GET  /api/roulette?days=30                          estado de cada mercado, entradas, resultado real e o simulado
 * POST /api/roulette/start?market=fx&horizon=60&model=B[&lot=0.01]   liga (sem lot = lote mínimo de cada símbolo)
 * POST /api/roulette/stop[?market=fx]                 para um mercado (sem market = todos)
 * </pre>
 */
@RestController
@Profile("!cli")
@RequestMapping("/api/roulette")
public class RouletteController {

    private final RouletteService roulette;
    private final OrderService orders;
    private final TradingProperties trading;

    public RouletteController(RouletteService roulette, OrderService orders, TradingProperties trading) {
        this.roulette = roulette;
        this.orders = orders;
        this.trading = trading;
    }

    @GetMapping
    public Map<String, Object> state(@RequestParam(defaultValue = "30") int days) {
        Map<String, Object> out = roulette.state(days);
        out.put("mode", trading.mode());
        out.put("blocked", orders.blocked());
        out.put("loss_profile", orders.lossProfile());
        return out;
    }

    @PostMapping("/start")
    public RouletteService.Config start(@RequestParam String market, @RequestParam int horizon,
                                       @RequestParam(defaultValue = "B") String model,
                                       @RequestParam(required = false) Double lot) {
        return roulette.start(market, horizon, model, lot);
    }

    @PostMapping("/stop")
    public Map<String, Object> stop(@RequestParam(required = false) String market) {
        if (market == null || market.isBlank()) roulette.stopAll("parada pelo operador (todas)");
        else roulette.stop(market, "parada pelo operador");
        return Map.of("stopped", market == null || market.isBlank() ? "todas" : market);
    }
}
