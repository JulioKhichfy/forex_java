package com.jevforex.app.api;

import com.jevforex.app.live.LivePredictionService;
import com.jevforex.app.live.PredictionRepository;
import com.jevforex.ml.ModelStore;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Previsões ao vivo para o dashboard (passo 6a). Só em 127.0.0.1 (server.address).
 *
 * <pre>
 * GET  /api/predictions/latest        a mais recente de cada par × modelo × horizonte, com a idade
 * GET  /api/predictions?symbol=&hours= histórico
 * GET  /api/predictions/scoreboard    placar ao vivo por modelo (desde a versão em produção)
 * POST /api/predictions/run           prevê agora (gatilho MANUAL: aparece no painel, fora do placar)
 * </pre>
 */
@RestController
@Profile("!cli")
@RequestMapping("/api/predictions")
public class PredictionController {

    private final PredictionRepository repo;
    private final LivePredictionService live;

    public PredictionController(PredictionRepository repo, LivePredictionService live) {
        this.repo = repo;
        this.live = live;
    }

    @GetMapping("/latest")
    public Map<String, Object> latest() {
        Map<String, Object> out = new LinkedHashMap<>();
        ModelStore.Loaded m = live.champion();
        out.put("model_version", m == null ? null : m.manifest().version());
        out.put("trained_until", m == null ? null : m.manifest().trainTo());
        out.put("lockbox_run", m == null ? null : m.manifest().lockboxRun());
        List<PredictionRepository.Prediction> rows = repo.latest("fx");
        out.put("age_minutes", rows.stream().map(PredictionRepository.Prediction::momentUtc).max(Instant::compareTo)
                .map(t -> Duration.between(t, Instant.now()).toMinutes()).orElse(null));
        out.put("predictions", rows);
        return out;
    }

    @GetMapping
    public List<PredictionRepository.Prediction> history(@RequestParam(required = false) String symbol,
                                                         @RequestParam(defaultValue = "24") int hours,
                                                         @RequestParam(defaultValue = "500") int limit) {
        return repo.history("fx", symbol, Instant.now().minus(Duration.ofHours(hours)), Math.min(limit, 5000));
    }

    @GetMapping("/scoreboard")
    public Map<String, Object> scoreboard(@RequestParam(defaultValue = "90") int days) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("since", Instant.now().minus(Duration.ofDays(days)).toString());
        out.put("models", repo.scoreboard("fx", Instant.now().minus(Duration.ofDays(days))));
        return out;
    }

    @PostMapping("/run")
    public LivePredictionService.Run runNow() {
        return live.predict(LocalDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MINUTES), "MANUAL");
    }
}
