package com.jevforex.app.live;

import com.jevforex.app.config.FeatureProperties;
import com.jevforex.app.config.SilverProperties;
import com.jevforex.core.risk.RiskSettings;
import com.jevforex.features.FeatureConfig;
import com.jevforex.lake.LakeSql;
import com.jevforex.lake.LakeStorage;
import com.jevforex.ml.Outcomes;
import com.jevforex.normalize.CandleNormalizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * Placar ao vivo (passo 6d): depois do horizonte, cada previsão ganha o que aconteceu — label (ALTA, QUEDA, LATERAL
 * pela mesma regra do gold) e o R da operação com stop/alvo/tempo ({@link Outcomes}, a mesma regra do experimento).
 * Lê o silver + o bronze recente. Sem barras depois de 3 dias: SEM_DADOS.
 */
@Component
public class PredictionResolver {

    private static final Logger log = LoggerFactory.getLogger(PredictionResolver.class);
    private static final Duration GIVE_UP = Duration.ofDays(3);

    private final PredictionRepository repo;
    private final LakeStorage lake;
    private final FeatureProperties featureProps;
    private final SilverProperties silver;
    private final RiskSettings risk;

    public PredictionResolver(PredictionRepository repo, LakeStorage lake, FeatureProperties featureProps,
                              SilverProperties silver, RiskSettings risk) {
        this.repo = repo;
        this.lake = lake;
        this.featureProps = featureProps;
        this.silver = silver;
        this.risk = risk;
    }

    /** Agenda o placar só no modo servidor (a CLI chama resolve() direto: resolve-predictions). */
    @Component
    @Profile("!cli")
    static class Scheduler {
        private final PredictionResolver resolver;

        Scheduler(PredictionResolver resolver) {
            this.resolver = resolver;
        }

        @Scheduled(initialDelayString = "PT1M", fixedDelayString = "PT5M")
        public void tick() {
            try {
                int n = resolver.resolve();
                if (n > 0) log.info("Placar: {} previsões resolvidas", n);
            } catch (RuntimeException e) {
                log.warn("Placar: falhou ({}); tenta de novo em 5 min", e.toString());
            }
        }
    }

    /** @return quantas previsões ganharam resultado */
    public synchronized int resolve() {
        List<PredictionRepository.Prediction> open = repo.unresolved(Instant.now(), 3000);
        if (open.isEmpty()) return 0;
        int n = 0;
        Map<String, List<PredictionRepository.Prediction>> byMarket = new java.util.TreeMap<>();
        for (PredictionRepository.Prediction p : open) byMarket.computeIfAbsent(p.market(), k -> new ArrayList<>()).add(p);
        for (var e : byMarket.entrySet()) n += resolve(e.getKey(), e.getValue());
        return n;
    }

    private int resolve(String market, List<PredictionRepository.Prediction> open) {
        FeatureConfig cfg = featureProps.toConfig();
        Instant first = open.stream().map(PredictionRepository.Prediction::momentUtc).min(Instant::compareTo).orElseThrow();
        Path candles = lake.root().resolve("silver/candles_m1");
        if (!Files.isDirectory(candles)) return 0;
        Map<String, double[]> outcome = new HashMap<>();
        try (LakeSql sql = LakeSql.open(lake.root().resolve("tmp").resolve("duckdb-resolve"), silver.duckdbMemory())) {
            String from = "TIMESTAMP '" + LocalDateTime.ofInstant(first.minus(Duration.ofDays(1)), ZoneOffset.UTC)
                    .toString().replace('T', ' ') + "'";
            String read = "read_parquet('" + LakeSql.slashes(candles) + "/**/*.parquet', hive_partitioning = true)";
            String maxSeen = sql.query("SELECT CAST(max(seen_utc) AS VARCHAR) FROM " + read + " WHERE market = '" + market + "' "
                    + "AND time_utc >= " + from, rs -> rs.getString(1)).get(0);
            Instant since = maxSeen == null ? Instant.EPOCH
                    : LocalDateTime.parse(maxSeen.replace(' ', 'T')).minusMinutes(10).toInstant(ZoneOffset.UTC);
            CandleNormalizer.createRecent(sql, lake.root(), since, "recent_candles");
            sql.execute("""
                    CREATE OR REPLACE TEMP TABLE m1o AS
                    WITH s AS (SELECT symbol, time_utc AS t, open, high, low, spread_points FROM %1$s
                                WHERE market = '%3$s' AND time_utc >= %2$s)
                    SELECT * FROM s
                    UNION ALL
                    SELECT symbol, time_utc, open, high, low, spread_points FROM recent_candles
                     WHERE market = '%3$s' AND time_utc >= %2$s
                       AND time_utc > coalesce((SELECT max(t) FROM s), TIMESTAMP '1970-01-01')
                    """.formatted(read, from, market));
            Outcomes.preparePoints(sql, lake.root(), market);

            LinkedHashMap<String, String> cols = new LinkedHashMap<>();
            cols.put("symbol", "VARCHAR");
            cols.put("moment_utc", "TIMESTAMP");
            cols.put("atr", "DOUBLE");
            cols.put("horizon", "INTEGER");
            List<Object[]> rows = new ArrayList<>();
            for (PredictionRepository.Prediction p : open) {
                if (p.atr() == null) continue;
                rows.add(new Object[]{p.symbol(), LocalDateTime.ofInstant(p.momentUtc(), ZoneOffset.UTC).toString()
                        .replace('T', ' '), p.atr(), p.horizonMin()});
            }
            if (rows.isEmpty()) return 0;
            sql.createTableFromRows("pred_moments", cols, rows, lake.root().resolve("tmp"));
            for (int h : new TreeSet<>(open.stream().map(PredictionRepository.Prediction::horizonMin).toList())) {
                Outcomes.createFrom(sql, """
                        SELECT DISTINCT symbol, moment_utc, atr, moment_utc + to_minutes(%d) AS want_in
                          FROM pred_moments WHERE horizon = %d
                        """.formatted(cfg.entryDelayMinutes(), h), "outc_" + h, h, risk.exits().stopAtr(),
                        risk.exits().targetR());
                sql.query("SELECT symbol, CAST(moment_utc AS VARCHAR), r_buy, r_sell, y_buy, y_sell FROM outc_" + h,
                        rs -> outcome.put(key(rs.getString(1), rs.getString(2), h), new double[]{
                                rs.getDouble(3), rs.getDouble(4), rs.getDouble(5), rs.getDouble(6)}));
            }
        }
        int n = 0;
        double th = cfg.labelThresholdAtr();
        for (PredictionRepository.Prediction p : open) {
            String m = LocalDateTime.ofInstant(p.momentUtc(), ZoneOffset.UTC).toString().replace('T', ' ');
            double[] o = outcome.get(key(p.symbol(), m.length() == 16 ? m + ":00" : m, p.horizonMin()));
            if (o != null) {
                String label = o[2] > th ? "ALTA" : o[3] > th ? "QUEDA" : "LATERAL";
                repo.resolve(p.id(), label, o[0], o[1]);
                n++;
            } else if (Duration.between(p.momentUtc(), Instant.now()).compareTo(GIVE_UP) > 0) {
                repo.resolve(p.id(), "SEM_DADOS", null, null);
                n++;
            }
        }
        return n;
    }

    private static String key(String symbol, String moment, int h) {
        return symbol + "|" + moment + "|" + h;
    }
}
