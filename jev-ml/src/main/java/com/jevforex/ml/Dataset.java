package com.jevforex.ml;

import com.jevforex.lake.LakeSql;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * Features do gold unidas aos labels de um horizonte, em memória, em ordem cronológica.
 * O par entra como colunas 0/1 (um modelo único para os 7 pares, cap. 10).
 */
public final class Dataset {

    /** Grupo A: preço, volatilidade, custo, sessão e fator USD. */
    public static final List<String> PRICE = List.of("atr_bps", "ret15_atr", "ret60_atr", "ret240_atr",
            "dist_sma50_h1_atr", "rsi14_m15", "day_range_atr", "regime", "spread_atr", "spread_rel", "hour_sin",
            "hour_cos", "dow", "sess_tokyo", "sess_london", "sess_ny", "usd_factor15_bps", "usd_factor60_bps",
            "resid15_bps", "resid60_bps");
    /** Grupo B: calendário (surpresa padronizada e proximidade de eventos). */
    public static final List<String> CALENDAR = List.of("surprise_base", "surprise_quote", "surprise_diff",
            "min_since_event", "min_to_event", "is_event");

    /** Grupo C: sinais do Jev por moeda (fset v2). */
    public static final List<String> TEXT = List.of("text_short_base", "text_short_quote", "text_short_diff",
            "text_long_base", "text_long_quote", "text_long_diff", "guidance_base", "guidance_quote", "text_docs_24h",
            "hours_since_text");

    /** Grupo TONE: surpresa de tom (tom − tom da divulgação anterior do mesmo banco e tipo; fset v3). */
    public static final List<String> TONE = List.of("tone_policy_short_base", "tone_policy_short_quote",
            "tone_policy_short_diff", "tone_policy_long_diff", "tone_speech_base", "tone_speech_quote",
            "tone_speech_diff");

    /**
     * A = preço; B = A + calendário; C = B + texto do Jev (documento mestre, capítulo 11);
     * D = B + surpresa de tom (teste da hipótese "o mercado reage à mudança de tom").
     */
    public static final Map<String, List<String>> MODELS;

    static {
        Map<String, List<String>> m = new LinkedHashMap<>();
        m.put("A", PRICE);
        m.put("B", concat(PRICE, CALENDAR));
        m.put("C", concat(concat(PRICE, CALENDAR), TEXT));
        m.put("D", concat(concat(PRICE, CALENDAR), TONE));
        MODELS = java.util.Collections.unmodifiableMap(m);
    }

    private static List<String> concat(List<String> a, List<String> b) {
        List<String> out = new ArrayList<>(a);
        out.addAll(b);
        return List.copyOf(out);
    }

    /** Classes na ordem usada pelo modelo. */
    public static final List<String> CLASSES = List.of("QUEDA", "LATERAL", "ALTA");
    public static final int DOWN = 0, FLAT = 1, UP = 2;

    final long[] moment;          // epoch s (UTC)
    final long[] labelAvailable;  // epoch s: quando o resultado ficou conhecido
    final String[] symbol;
    final boolean[] event;
    final double[][] x;           // colunas = columns
    final List<String> columns;
    final int[] y;
    final double[] yBuy, ySell;
    int missingValues;
    /** O gold tem o grupo C (fset v2 ou depois)? */
    boolean hasText;
    /** O gold tem a surpresa de tom (fset v3 ou depois)? */
    boolean hasTone;

    Dataset(int n, List<String> columns) {
        moment = new long[n];
        labelAvailable = new long[n];
        symbol = new String[n];
        event = new boolean[n];
        x = new double[n][columns.size()];
        this.columns = columns;
        y = new int[n];
        yBuy = new double[n];
        ySell = new double[n];
    }

    public int size() {
        return y.length;
    }

    /** Índices das colunas de um conjunto de features (os pares sempre entram). */
    int[] columnsOf(List<String> features) {
        List<Integer> idx = new ArrayList<>();
        for (int j = 0; j < columns.size(); j++) {
            String c = columns.get(j);
            if (features.contains(c) || c.startsWith("pair_")) idx.add(j);
        }
        return idx.stream().mapToInt(Integer::intValue).toArray();
    }

    List<String> names(int[] cols) {
        List<String> out = new ArrayList<>();
        for (int c : cols) out.add(columns.get(c));
        return out;
    }

    public static Dataset load(LakeSql sql, Path lakeRoot, String fset, int horizon, LocalDate from) {
        Path features = lakeRoot.resolve("gold/features/market=fx/fset=" + fset);
        Path labels = lakeRoot.resolve("gold/labels/market=fx/fset=" + fset);
        if (!Files.isDirectory(features) || !Files.isDirectory(labels)) {
            throw new IllegalStateException("Sem gold fset=" + fset + ". Rode antes: features");
        }
        List<String> feats = new ArrayList<>(PRICE);
        feats.addAll(CALENDAR);
        List<String> available = sql.query("DESCRIBE SELECT * FROM read_parquet('" + LakeSql.slashes(features)
                + "/**/*.parquet', hive_partitioning = true)", rs -> rs.getString(1));
        boolean hasText = available.containsAll(TEXT);
        if (hasText) feats.addAll(TEXT);
        boolean hasTone = available.containsAll(TONE);
        if (hasTone) feats.addAll(TONE);
        String select = String.join(", ", feats.stream()
                .map(c -> c.equals("is_event") ? "CAST(f.kind = 'EVENT' AS DOUBLE) AS is_event" : "f." + c).toList());
        sql.execute("""
                CREATE OR REPLACE TEMP TABLE ds AS
                SELECT CAST(epoch(f.moment_utc) AS BIGINT) AS m, CAST(epoch(l.label_available_utc) AS BIGINT) AS la,
                       f.symbol, f.kind = 'EVENT' AS ev, %s, l.label, l.y_buy, l.y_sell
                  FROM read_parquet('%s/**/*.parquet', hive_partitioning = true) f
                  JOIN read_parquet('%s/**/*.parquet', hive_partitioning = true) l
                    ON l.symbol = f.symbol AND l.moment_utc = f.moment_utc
                 WHERE l.horizon_min = %d AND f.moment_utc >= TIMESTAMP '%s 00:00:00'
                """.formatted(select, LakeSql.slashes(features), LakeSql.slashes(labels), horizon, from));
        List<String> pairs = new ArrayList<>(new TreeSet<>(sql.query("SELECT DISTINCT symbol FROM ds",
                rs -> rs.getString(1))));
        List<String> columns = new ArrayList<>(feats);
        pairs.forEach(p -> columns.add("pair_" + p));
        int n = (int) sql.scalar("SELECT count(*) FROM ds");
        Dataset d = new Dataset(n, List.copyOf(columns));
        d.hasText = hasText;
        d.hasTone = hasTone;
        int[] row = {0};
        sql.query("SELECT * FROM ds ORDER BY m, symbol", rs -> {
            int i = row[0]++;
            d.moment[i] = rs.getLong("m");
            d.labelAvailable[i] = rs.getLong("la");
            d.symbol[i] = rs.getString("symbol");
            d.event[i] = rs.getBoolean("ev");
            for (int j = 0; j < feats.size(); j++) {
                double v = rs.getDouble(feats.get(j));
                if (rs.wasNull() || Double.isNaN(v) || Double.isInfinite(v)) {
                    v = 0;   // raro (0% no fset v1); a árvore trata 0 como um valor qualquer
                    d.missingValues++;
                }
                d.x[i][j] = v;
            }
            int p = pairs.indexOf(d.symbol[i]);
            d.x[i][feats.size() + p] = 1;
            d.y[i] = CLASSES.indexOf(rs.getString("label"));
            d.yBuy[i] = rs.getDouble("y_buy");
            d.ySell[i] = rs.getDouble("y_sell");
            return null;
        });
        sql.execute("DROP TABLE ds");   // os dados já estão em memória no Java: libera o DuckDB
        return d;
    }

    static long epoch(LocalDate date) {
        return date.atStartOfDay().toEpochSecond(ZoneOffset.UTC);
    }
}
