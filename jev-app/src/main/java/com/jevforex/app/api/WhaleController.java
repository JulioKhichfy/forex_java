package com.jevforex.app.api;

import com.jevforex.app.live.WhaleService;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Baleias no bitcoin (página Radar). Só em 127.0.0.1.
 *
 * <pre>
 * GET /api/whales?hours=24     as baleias recentes e a configuração
 * </pre>
 */
@RestController
@Profile("!cli")
@RequestMapping("/api/whales")
public class WhaleController {

    private final WhaleService whales;

    public WhaleController(WhaleService whales) {
        this.whales = whales;
    }

    @GetMapping
    public Map<String, Object> recent(@RequestParam(defaultValue = "24") int hours) {
        return whales.recent(hours);
    }
}
