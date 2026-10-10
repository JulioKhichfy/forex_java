package com.jevforex.app.live;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jevforex.app.config.ExperimentProperties;
import com.jevforex.app.config.FeatureProperties;
import com.jevforex.app.config.SilverProperties;
import com.jevforex.core.risk.RiskSettings;
import com.jevforex.features.FeatureBuilder;
import com.jevforex.lake.LakeSql;
import com.jevforex.lake.LakeStorage;
import com.jevforex.ml.ExperimentConfig;
import com.jevforex.ml.Gbm;
import com.jevforex.ml.ModelStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Previsões ao vivo (passo 6a): as features do instante com o MESMO código do treino
 * ({@link FeatureBuilder#live}), os modelos em produção (champion) e o gate 4 do experimento.
 * O resultado é apoio à decisão — quem decide e clica é o operador.
 */
@Service
public class LivePredictionService {

    private static final Logger log = LoggerFactory.getLogger(LivePredictionService.class);

    private final LakeStorage lake;
    private final FeatureProperties featureProps;
    private final ExperimentProperties experimentProps;
    private final SilverProperties silver;
    private final RiskSettings risk;
    private final LiveProperties props;
    private final PredictionRepository repo;
    private final ObjectMapper mapper;
    private final Map<String, ModelStore.Loaded> models = new java.util.HashMap<>();   // versão em produção por mercado

    public LivePredictionService(LakeStorage lake, FeatureProperties featureProps, ExperimentProperties experimentProps,
                                 SilverProperties silver, RiskSettings risk, LiveProperties props,
                                 PredictionRepository repo, ObjectMapper mapper) {
        this.lake = lake;
        this.featureProps = featureProps;
        this.experimentProps = experimentProps;
        this.silver = silver;
        this.risk = risk;
        this.props = props;
        this.repo = repo;
        this.mapper = mapper;
    }

    /**
     * @param rows     previsões gravadas (par × modelo × horizonte)
     * @param symbols  pares com features válidas (barra fresca, ATR, regime); os demais ficaram de fora
     * @param note     por que não houve previsão (mercado fechado, sem modelo…), se for o caso
     */
    public record Run(LocalDateTime at, String trigger, String modelVersion, int rows, List<String> symbols,
                      String lastBarUtc, String note) {
    }

    /** Modelos em produção do forex (compatibilidade). */
    public ModelStore.Loaded champion() {
        return champion("fx");
    }

    /** Modelos em produção de um mercado; recarrega se a versão promovida mudou. null = sem modelo. */
    public synchronized ModelStore.Loaded champion(String market) {
        ModelStore store = new ModelStore(lake.root(), market);
        String version = store.champion().map(ModelStore.Champion::version).orElse(null);
        if (version == null) return null;
        ModelStore.Loaded cached = models.get(market);
        if (cached == null || !cached.manifest().version().equals(version)) {
            cached = store.load(version);
            models.put(market, cached);
            log.info("Modelos em produção de {} carregados: versão {}", market, version);
        }
        return cached;
    }

    /** Prevê em todos os mercados com modelo em produção (experiment.markets). */
    public synchronized Run predict(LocalDateTime at, String trigger) {
        int rows = 0;
        List<String> symbols = new ArrayList<>(), notes = new ArrayList<>(), versions = new ArrayList<>();
        String last = null;
        for (String market : experimentProps.marketList()) {
            Run r = predict(market, at, trigger);
            rows += r.rows();
            symbols.addAll(r.symbols());
            if (r.modelVersion() != null) versions.add(market + " " + r.modelVersion());
            if (r.note() != null) notes.add(market + ": " + r.note());
            if (r.lastBarUtc() != null && (last == null || r.lastBarUtc().compareTo(last) > 0)) last = r.lastBarUtc();
        }
        return new Run(at, trigger, versions.isEmpty() ? null : String.join(" · ", versions), rows, symbols, last,
                rows > 0 ? null : notes.isEmpty() ? "sem previsão" : String.join(" | ", notes));
    }

    public synchronized Run predict(String market, LocalDateTime at, String trigger) {
        ModelStore.Loaded m = champion(market);
        if (m == null) return new Run(at, trigger, null, 0, List.of(), null,
                "Sem modelo em produção. Rode: train-champion --market=" + market);
        ExperimentConfig.Decision gate = experimentProps.toConfig(featureProps.toConfig().fset(),
                risk.exits()).decision();
        FeatureBuilder.Live live;
        try (LakeSql sql = LakeSql.open(lake.root().resolve("tmp").resolve("duckdb-live"), silver.duckdbMemory())) {
            live = new FeatureBuilder(lake.root(), featureProps.toConfig(), market)
                    .live(sql, at, props.lookbackDays(), silver.calendar().actualLatencySeconds());
        }
        Instant moment = at.toInstant(ZoneOffset.UTC);
        Integer lag = live.lastBarUtc() == null ? null : (int) Duration.between(
                LocalDateTime.parse(live.lastBarUtc().replace(' ', 'T')).plusMinutes(1).toInstant(ZoneOffset.UTC),
                moment).toSeconds();
        if (live.rows().isEmpty()) {
            return new Run(at, trigger, m.manifest().version(), 0, List.of(), live.lastBarUtc(),
                    "Nenhum símbolo com dados frescos (mercado fechado ou MT5 parado): sem previsão");
        }
        int n = 0;
        List<String> symbols = new ArrayList<>();
        for (Map<String, Object> row : live.rows()) {
            String symbol = (String) row.get("symbol");
            symbols.add(symbol);
            FeatureBuilder.LastBar bar = live.lastBars().get(symbol);
            for (ModelStore.Entry e : m.manifest().entries()) {
                Gbm g = m.get(e.model(), e.horizon());
                double[] x = vector(e.features(), row, symbol);
                double[] p = g.predict(new double[][]{x})[0];
                ObjectNode feats = mapper.createObjectNode();
                for (int i = 0; i < x.length; i++) feats.put(e.features().get(i), x[i]);
                boolean saved = repo.insert(new PredictionRepository.Prediction(null, market, symbol, moment,
                        (String) row.get("kind"), trigger, e.model(), e.horizon(), m.manifest().version(), p[0], p[1],
                        p[2], signal(p, gate), number(row.get("atr")), bar == null ? null : bar.close(),
                        bar == null ? null : bar.spreadPoints(), lag, feats.toString(), null, null, null, null, null));
                if (saved) n++;
            }
        }
        log.info("Previsões {} {} {}: {} gravadas ({} símbolos, modelos {})", market, trigger, at, n, symbols.size(),
                m.models().keySet());
        return new Run(at, trigger, m.manifest().version(), n, symbols, live.lastBarUtc(), null);
    }

    /** O vetor na ordem exata do treino: pares viram 0/1, is_event vem do tipo do momento, nulo vira 0 (como no Dataset). */
    static double[] vector(List<String> features, Map<String, Object> row, String symbol) {
        double[] x = new double[features.size()];
        for (int i = 0; i < x.length; i++) {
            String f = features.get(i);
            if (f.startsWith("pair_")) x[i] = f.equals("pair_" + symbol) ? 1 : 0;
            else if (f.equals("is_event")) x[i] = "EVENT".equals(row.get("kind")) ? 1 : 0;
            else {
                Double v = number(row.get(f));
                x[i] = v == null || v.isNaN() || v.isInfinite() ? 0 : v;
            }
        }
        return x;
    }

    /** Gate 4 (cap. 12): P ≥ mínimo e margem sobre a classe oposta. */
    static String signal(double[] p, ExperimentConfig.Decision gate) {
        double down = p[0], up = p[2];
        if (up >= gate.minProb() && up - down >= gate.minMargin()) return "BUY";
        if (down >= gate.minProb() && down - up >= gate.minMargin()) return "SELL";
        return "NO_TRADE";
    }

    private static Double number(Object o) {
        if (o instanceof Number n) return n.doubleValue();
        if (o instanceof Boolean b) return b ? 1.0 : 0.0;
        return null;
    }
}
