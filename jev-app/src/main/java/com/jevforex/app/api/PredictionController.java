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
 * GET  /api/predictions/signals?minutes=15   sinais recentes das regras de sinal de TODOS os mercados (vigia global)
 * GET  /api/predictions?symbol=&hours= histórico
 * GET  /api/predictions/scoreboard    placar ao vivo por modelo (desde a versão em produção)
 * POST /api/predictions/run           prevê agora (gatilho MANUAL: aparece no painel, fora do placar)
 * GET  /api/predictions/backtest?market=fx&period=48h&horizon=60&model=B[&symbol=EURUSD][&lot=0.01]
 *                                      [&side=all|BUY|SELL][&minProb=0][&skipFlat=true]
 *                                      e se cada previsão resolvida tivesse virado ordem: acertos, derrotas, R e US$
 * </pre>
 */
@RestController
@Profile("!cli")
@RequestMapping("/api/predictions")
public class PredictionController {

    private final PredictionRepository repo;
    private final LivePredictionService live;
    private final com.jevforex.app.trade.PredictionBacktest backtest;

    public PredictionController(PredictionRepository repo, LivePredictionService live,
                                com.jevforex.app.trade.PredictionBacktest backtest) {
        this.backtest = backtest;
        this.repo = repo;
        this.live = live;
    }

    @GetMapping("/latest")
    public Map<String, Object> latest(@RequestParam(defaultValue = "fx") String market) {
        Map<String, Object> out = new LinkedHashMap<>();
        ModelStore.Loaded m = live.champion(market);
        out.put("market", market);
        out.put("model_version", m == null ? null : m.manifest().version());
        out.put("trained_until", m == null ? null : m.manifest().trainTo());
        out.put("lockbox_run", m == null ? null : m.manifest().lockboxRun());
        out.put("rule", live.rule(market));   // regra de sinal do mercado (null = gate 4)
        List<PredictionRepository.Prediction> rows = repo.latest(market);
        out.put("age_minutes", rows.stream().map(PredictionRepository.Prediction::momentUtc).max(Instant::compareTo)
                .map(t -> Duration.between(t, Instant.now()).toMinutes()).orElse(null));
        out.put("predictions", rows);
        return out;
    }

    /**
     * Modo só sinais (20/10/2026): os sinais das regras de mercado (live.signal-rules) com até {@code minutes} minutos,
     * de todos os mercados com regra — o dashboard avisa em qualquer página.
     */
    @GetMapping("/signals")
    public Map<String, Object> signals(@RequestParam(defaultValue = "15") int minutes) {
        Instant from = Instant.now().minus(Duration.ofMinutes(Math.min(Math.max(minutes, 1), 240)));
        List<Map<String, Object>> out = new java.util.ArrayList<>();
        live.rules().forEach((market, rule) -> {
            for (PredictionRepository.Prediction p : repo.latest(market)) {
                if ("NO_TRADE".equals(p.signal()) || !p.model().equals(rule.model()) || p.horizonMin() != rule.horizon()
                        || p.momentUtc().isBefore(from)) continue;
                Map<String, Object> s = new LinkedHashMap<>();
                s.put("market", market);
                s.put("prediction", p);
                out.add(s);
            }
        });
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("rules", live.rules());
        res.put("signals", out);
        return res;
    }

    @GetMapping
    public List<PredictionRepository.Prediction> history(@RequestParam(defaultValue = "fx") String market,
                                                         @RequestParam(required = false) String symbol,
                                                         @RequestParam(defaultValue = "24") int hours,
                                                         @RequestParam(defaultValue = "500") int limit) {
        return repo.history(market, symbol, Instant.now().minus(Duration.ofHours(hours)), Math.min(limit, 5000));
    }

    @GetMapping("/backtest")
    public com.jevforex.app.trade.PredictionBacktest.Result backtest(
            @RequestParam(defaultValue = "fx") String market, @RequestParam(required = false) String symbol,
            @RequestParam(defaultValue = "48h") String period, @RequestParam(defaultValue = "60") int horizon,
            @RequestParam(defaultValue = "B") String model, @RequestParam(defaultValue = "0.01") double lot,
            @RequestParam(defaultValue = "all") String side, @RequestParam(defaultValue = "0") double minProb,
            @RequestParam(defaultValue = "true") boolean skipFlat) {
        if (!List.of(15, 60).contains(horizon)) throw new IllegalArgumentException("horizon deve ser 15 ou 60");
        if (!List.of("all", "BUY", "SELL").contains(side)) throw new IllegalArgumentException("side deve ser all, BUY ou SELL");
        return backtest.run(new com.jevforex.app.trade.PredictionBacktest.Query(market,
                symbol == null || symbol.isBlank() ? null : symbol, period, horizon, model, lot, side, minProb, skipFlat));
    }

    @GetMapping("/scoreboard")
    public Map<String, Object> scoreboard(@RequestParam(defaultValue = "fx") String market,
                                          @RequestParam(defaultValue = "90") int days) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("since", Instant.now().minus(Duration.ofDays(days)).toString());
        out.put("models", repo.scoreboard(market, Instant.now().minus(Duration.ofDays(days))));
        return out;
    }

    @PostMapping("/run")
    public LivePredictionService.Run runNow() {
        return live.predict(LocalDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MINUTES), "MANUAL");
    }
}
