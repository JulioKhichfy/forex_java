package com.jevforex.app.api;

import com.jevforex.app.config.TradingProperties;
import com.jevforex.app.trade.CasinoService;
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
 * Cassino (página Cassino). Só em 127.0.0.1.
 *
 * <pre>
 * GET  /api/casino                                      linhas, global, rodada, resultado e últimas tentativas
 * POST /api/casino/start?symbol=XAUUSD&side=BUY|auto=true&takeUsd=0.1&stopUsd=1&times=5&attempts=3[&lot=0.01][&closeAfter=15]
 * POST /api/casino/configs                             salva a configuração por par (JSON: símbolo → configuração)
 * POST /api/casino/stop?symbol=XAUUSD                   desliga a linha (a posição aberta continua com SL/TP)
 * POST /api/casino/stop-all[?close=true]                desliga todas (close=true: fecha as posições do Cassino)
 * POST /api/casino/global?[takeUsd=1][&lossUsd=2]&rounds=3
 * GET  /api/casino/backtest?symbol=…&side=…|auto=true&takeUsd=…&stopUsd=…[&lot=…][&closeAfter=…]   últimos 4 quartos de hora
 * </pre>
 */
@RestController
@Profile("!cli")
@RequestMapping("/api/casino")
public class CasinoController {

    private final CasinoService casino;
    private final OrderService orders;
    private final TradingProperties trading;

    public CasinoController(CasinoService casino, OrderService orders, TradingProperties trading) {
        this.casino = casino;
        this.orders = orders;
        this.trading = trading;
    }

    @GetMapping
    public Map<String, Object> view() {
        Map<String, Object> out = casino.view();
        out.put("mode", trading.mode());
        out.put("blocked", orders.blocked());
        out.put("loss_limits", orders.lossProfile());
        return out;
    }

    @PostMapping("/start")
    public ResponseEntity<Object> start(@RequestParam String symbol, @RequestParam(defaultValue = "BUY") String side,
                                        @RequestParam(defaultValue = "false") boolean auto,
                                        @RequestParam(required = false) Double lot, @RequestParam double takeUsd,
                                        @RequestParam double stopUsd, @RequestParam(defaultValue = "1") int attempts,
                                        @RequestParam(defaultValue = "1") int times,
                                        @RequestParam(required = false) Integer closeAfter) {
        try {
            return ResponseEntity.ok(casino.start(symbol, side, auto, lot, takeUsd, stopUsd, attempts, times, closeAfter));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(409).body(Map.of("message", e.getMessage()));
        }
    }

    /** Salva a configuração de um ou mais pares: {"XAUUSD": {side, auto, lot, takeUsd, stopUsd, times, attempts, closeAfter}}. */
    @PostMapping("/configs")
    public ResponseEntity<Object> saveConfigs(@org.springframework.web.bind.annotation.RequestBody
                                              Map<String, CasinoService.Config> configs) {
        try {
            return ResponseEntity.ok(casino.saveConfigs(configs));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(409).body(Map.of("message", e.getMessage()));
        }
    }

    @PostMapping("/stop")
    public Object stop(@RequestParam String symbol) {
        return casino.stop(symbol, "desligada pelo operador");
    }

    @PostMapping("/stop-all")
    public Object stopAll(@RequestParam(defaultValue = "false") boolean close) {
        return casino.stopAll(close ? "parado pelo operador, posições fechando" : "parado pelo operador", close);
    }

    @PostMapping("/global")
    public ResponseEntity<Object> global(@RequestParam(required = false) Double takeUsd,
                                         @RequestParam(required = false) Double lossUsd,
                                         @RequestParam(defaultValue = "1") int rounds) {
        try {
            return ResponseEntity.ok(casino.setGlobal(takeUsd, lossUsd, rounds));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(409).body(Map.of("message", e.getMessage()));
        }
    }

    @GetMapping("/backtest")
    public ResponseEntity<Object> backtest(@RequestParam String symbol, @RequestParam(defaultValue = "BUY") String side,
                                           @RequestParam(defaultValue = "false") boolean auto,
                                           @RequestParam(required = false) Double lot, @RequestParam double takeUsd,
                                           @RequestParam double stopUsd, @RequestParam(required = false) Integer closeAfter) {
        try {
            return ResponseEntity.ok(casino.backtest(symbol, side, auto, lot, takeUsd, stopUsd, closeAfter));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(409).body(Map.of("message", e.getMessage()));
        }
    }
}
