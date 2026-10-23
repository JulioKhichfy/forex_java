package com.jevforex.features.scan;

import com.jevforex.lake.LakeSql;
import com.jevforex.normalize.CandleNormalizer;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Dados do radar: M1 do silver dos últimos dias + o que chegou ao bronze depois do último normalize (o mesmo
 * caminho das previsões ao vivo), agregado em M5, M15, H1, H4 e D1. Só lê o lake.
 *
 * <p>Incremental: a conexão fica aberta entre as leituras; o silver de cada mercado é lido uma vez por hora e, do
 * bronze, só os arquivos que ainda não foram lidos (o bronze tem um arquivo por barra: ler tudo a cada vez levava
 * ~10 s). Quando o normalize roda de novo, tudo é relido.</p>
 */
public final class ScanLoader {

    /** Barras por período que vão para o cálculo (M15: ~20 dias, para o volume do mesmo horário). */
    private static final Map<Tf, Integer> KEEP = Map.of(Tf.M5, 400, Tf.M15, 2200, Tf.H1, 600, Tf.H4, 300, Tf.D1, 100);
    private static final Duration SILVER_TTL = Duration.ofHours(1);

    private final LakeSql sql;
    private final Path lakeRoot;
    private final Set<Path> loadedFiles = new HashSet<>();
    private final Map<String, Instant> silverLoadedAt = new HashMap<>();
    private Instant builtAt;
    private boolean started;

    /** @param sql conexão só deste leitor (as tabelas scan_* ficam nela) */
    public ScanLoader(LakeSql sql, Path lakeRoot) {
        this.sql = sql;
        this.lakeRoot = lakeRoot;
    }

    /**
     * @param inputs      entrada do {@link Scanner} por símbolo, em ordem alfabética
     * @param recentFiles arquivos do bronze em memória além do silver
     * @param newFiles    quantos deles foram lidos nesta chamada
     */
    public record Loaded(Map<String, Scanner.Input> inputs, int recentFiles, int newFiles, Map<String, Long> ms) {
    }

    /**
     * @param symbols      universo do mercado (null = todos os do silver)
     * @param at           agora (UTC): barras a partir daqui não entram; período que já acabou = barra fechada
     * @param lookbackDays dias de M1 lidos (o D1 precisa de ~20 pregões para Bollinger e a média de amplitude)
     */
    public Loaded load(String market, List<String> symbols, LocalDateTime at, int lookbackDays) {
        if (!market.matches("[a-z]+")) throw new IllegalArgumentException("mercado inválido: " + market);
        Path candles = lakeRoot.resolve("silver").resolve(CandleNormalizer.TABLE);
        if (!Files.isDirectory(candles)) throw new IllegalStateException("Sem silver de candles. Rode antes: normalize");
        Instant marker = CandleNormalizer.builtAt(lakeRoot).orElse(null);
        if (!started || !Objects.equals(marker, builtAt)) reset(marker);

        Map<String, Long> ms = new LinkedHashMap<>();
        long t0 = System.nanoTime();
        LocalDateTime from = at.minusDays(lookbackDays);
        // uma tabela por mercado E universo: o Cassino pede um símbolo fora do radar (23/10/2026)
        String silverTable = "scan_silver_" + market + (symbols == null || symbols.isEmpty() ? ""
                : "_" + Integer.toHexString(String.join(",", symbols).hashCode()));
        Instant loadedAt = silverLoadedAt.get(silverTable);
        if (loadedAt == null || loadedAt.plus(SILVER_TTL).isBefore(Instant.now())) {
            LocalDateTime f = from.minusDays(1);
            List<String> files = silverMonths(candles, market, symbols, f, at);
            sql.execute(files.isEmpty()
                    ? "CREATE OR REPLACE TEMP TABLE " + silverTable + " AS SELECT NULL::VARCHAR AS symbol, "
                    + "NULL::TIMESTAMP AS t, NULL::DOUBLE AS open, NULL::DOUBLE AS high, NULL::DOUBLE AS low, "
                    + "NULL::DOUBLE AS close, NULL::BIGINT AS vol, NULL::INTEGER AS spread WHERE false"
                    : """
                    CREATE OR REPLACE TEMP TABLE %s AS
                    SELECT symbol, time_utc AS t, open, high, low, close, coalesce(tick_volume, 0) AS vol,
                           spread_points AS spread
                      FROM read_parquet([%s], hive_partitioning = true)
                     WHERE time_utc >= %s
                    """.formatted(silverTable, String.join(", ", files), ts(f)));
            silverLoadedAt.put(silverTable, Instant.now());
        }
        ms.put("silver", (System.nanoTime() - t0) / 1_000_000);
        t0 = System.nanoTime();
        int newFiles = readNewBronze(marker);
        ms.put("bronze", (System.nanoTime() - t0) / 1_000_000);
        t0 = System.nanoTime();

        String atSql = ts(at), fromSql = ts(from);
        String filter = symbols == null || symbols.isEmpty() ? "" : " AND symbol IN (" + symbols.stream()
                .filter(s -> s.matches("[A-Za-z0-9._]+")).map(s -> "'" + s + "'").collect(Collectors.joining(", ")) + ")";
        sql.execute("""
                CREATE OR REPLACE TEMP TABLE scan_m1 AS
                SELECT * EXCLUDE (src, seen) FROM (
                    SELECT *, 0 AS src, NULL::TIMESTAMP AS seen FROM %s WHERE t >= %s AND t < %s%s
                    UNION ALL
                    SELECT symbol, time_utc, open, high, low, close, coalesce(tick_volume, 0), spread_points, 1, seen_utc
                      FROM scan_recent WHERE market = '%s' AND time_utc >= %s AND time_utc < %s%s)
                QUALIFY row_number() OVER (PARTITION BY symbol, t ORDER BY src, seen) = 1
                """.formatted(silverTable, fromSql, atSql, filter, market, fromSql, atSql, filter));

        // D1 = dia UTC; o domingo à noite (abertura do forex, ouro, índices futuros) entra na segunda. Bitcoin: 7 dias.
        String day = "crypto".equals(market) ? "CAST(t AS DATE)"
                : "CAST(t AS DATE) + CASE WHEN dayofweek(t) = 0 THEN 1 ELSE 0 END";
        sql.execute("""
                CREATE OR REPLACE TEMP TABLE scan_tf AS
                WITH tfs(tf, mins) AS (VALUES ('M5', 5), ('M15', 15), ('H1', 60), ('H4', 240)),
                bars AS (
                    SELECT tf, mins, symbol, time_bucket(to_minutes(CAST(mins AS BIGINT)), t) AS b,
                           arg_min(open, t) AS o, max(high) AS h, min(low) AS l, arg_max(close, t) AS c, sum(vol) AS v
                      FROM scan_m1 CROSS JOIN tfs GROUP BY ALL
                    UNION ALL
                    SELECT 'D1', 1440, symbol, CAST(%s AS TIMESTAMP) AS b,
                           arg_min(open, t), max(high), min(low), arg_max(close, t), sum(vol)
                      FROM scan_m1 GROUP BY ALL)
                SELECT *, b + to_minutes(CAST(mins AS BIGINT)) <= %s AS closed,
                       row_number() OVER (PARTITION BY symbol, tf ORDER BY b DESC) AS rn
                  FROM bars
                """.formatted(day, atSql));

        ms.put("agregar", (System.nanoTime() - t0) / 1_000_000);
        t0 = System.nanoTime();
        Map<String, Map<Tf, List<Bar>>> bars = new LinkedHashMap<>();
        String keep = KEEP.entrySet().stream().map(e -> "WHEN '" + e.getKey() + "' THEN " + e.getValue())
                .collect(Collectors.joining(" ", "CASE tf ", " END"));
        sql.query("SELECT symbol, tf, CAST(b AS VARCHAR), o, h, l, c, v, closed FROM scan_tf WHERE rn <= " + keep
                        + " ORDER BY symbol, tf, b",
                rs -> bars.computeIfAbsent(rs.getString(1), k -> new EnumMap<>(Tf.class))
                        .computeIfAbsent(Tf.valueOf(rs.getString(2)), k -> new ArrayList<>())
                        .add(new Bar(parse(rs.getString(3)), rs.getDouble(4), rs.getDouble(5), rs.getDouble(6),
                                rs.getDouble(7), rs.getLong(8), rs.getBoolean(9))));
        Map<String, List<Bar>> m1 = new HashMap<>();
        sql.query("""
                SELECT symbol, CAST(t AS VARCHAR), open, high, low, close, vol FROM scan_m1
                QUALIFY t >= max(t) OVER (PARTITION BY symbol) - INTERVAL 26 HOUR
                 ORDER BY symbol, t
                """, rs -> m1.computeIfAbsent(rs.getString(1), k -> new ArrayList<>())
                .add(new Bar(parse(rs.getString(2)), rs.getDouble(3), rs.getDouble(4), rs.getDouble(5),
                        rs.getDouble(6), rs.getLong(7), true)));
        // spread agora × mediana da mesma hora UTC nos 20 dias anteriores (a mesma ideia do gate 3)
        Map<String, Object[]> spreads = new HashMap<>();
        sql.query("""
                WITH l AS (SELECT symbol, max(t) AS lt, arg_max(spread, t) AS sp FROM scan_m1 GROUP BY symbol)
                SELECT l.symbol, l.sp, median(m.spread) AS typ
                  FROM l LEFT JOIN scan_m1 m ON m.symbol = l.symbol AND hour(m.t) = hour(l.lt)
                                           AND m.t < l.lt - INTERVAL 1 HOUR AND m.t >= l.lt - INTERVAL 20 DAY
                 GROUP BY l.symbol, l.sp
                """, rs -> spreads.put(rs.getString(1), new Object[]{rs.getObject(2), rs.getObject(3)}));
        Map<String, String> profitCcy = profitCurrencies(market);

        Map<String, Scanner.Input> inputs = new LinkedHashMap<>();
        for (var e : bars.entrySet()) {
            String s = e.getKey();
            Object[] sp = spreads.getOrDefault(s, new Object[]{null, null});
            inputs.put(s, new Scanner.Input(s, currencies(market, s, profitCcy), e.getValue(),
                    m1.getOrDefault(s, List.of()), sp[0] == null ? null : ((Number) sp[0]).intValue(),
                    sp[1] == null ? null : ((Number) sp[1]).doubleValue()));
        }
        ms.put("ler", (System.nanoTime() - t0) / 1_000_000);
        return new Loaded(inputs, loadedFiles.size(), newFiles, ms);
    }

    /**
     * As pastas de mês do silver (market=/symbol=/year=/month=) que cobrem o período, só do mercado e dos símbolos
     * pedidos. Um glob no silver inteiro listava os arquivos de 100+ símbolos a cada leitura (~3 s).
     */
    static List<String> silverMonths(Path candles, String market, List<String> symbols, LocalDateTime from,
                                     LocalDateTime to) {
        Path dir = candles.resolve("market=" + market);
        if (!Files.isDirectory(dir)) return List.of();
        List<String> out = new ArrayList<>();
        try (var s = Files.list(dir)) {
            for (Path sd : s.sorted().toList()) {
                String name = sd.getFileName().toString();
                if (!name.startsWith("symbol=")) continue;
                if (symbols != null && !symbols.isEmpty() && !symbols.contains(name.substring(7))) continue;
                for (YearMonth ym = YearMonth.from(from); !ym.isAfter(YearMonth.from(to)); ym = ym.plusMonths(1)) {
                    Path m = sd.resolve("year=" + ym.getYear()).resolve("month=" + ym.getMonthValue());
                    if (Files.isDirectory(m)) out.add("'" + LakeSql.slashes(m) + "/*.parquet'");
                }
            }
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
        return out;
    }

    /** Normalize novo (ou primeira leitura): o silver mudou e o bronze recente passa a ser outro. */
    private void reset(Instant marker) {
        builtAt = marker;
        started = true;
        loadedFiles.clear();
        silverLoadedAt.clear();
        CandleNormalizer.createFrom(sql, List.of(), "scan_recent");
    }

    /** Lê do bronze só os arquivos novos (gravados depois do último normalize e ainda não lidos). */
    private int readNewBronze(Instant marker) {
        Instant since = marker != null ? marker.minus(Duration.ofMinutes(5)) : Instant.now().minus(Duration.ofDays(3));
        List<Path> fresh = CandleNormalizer.recentFiles(lakeRoot, since).stream()
                .filter(p -> !loadedFiles.contains(p)).toList();
        if (fresh.isEmpty()) return 0;
        List<Path> read = CandleNormalizer.createFrom(sql, fresh, "scan_new");
        if (!read.isEmpty()) sql.execute("INSERT INTO scan_recent SELECT * FROM scan_new");
        loadedFiles.addAll(read);
        return read.size();
    }

    /** Moedas cujo calendário e notícias mexem com o símbolo: as duas do par no forex; senão a moeda de lucro. */
    static List<String> currencies(String market, String symbol, Map<String, String> profitCcy) {
        if ("fx".equals(market) && symbol.length() == 6) return List.of(symbol.substring(0, 3), symbol.substring(3));
        String c = profitCcy.get(symbol);
        return List.of(c == null || c.isBlank() ? "USD" : c);
    }

    private Map<String, String> profitCurrencies(String market) {
        Path specs = lakeRoot.resolve("silver/instrument_specs/part-0.parquet");
        Map<String, String> out = new HashMap<>();
        if (!Files.exists(specs) || "fx".equals(market)) return out;
        if (!sql.query("DESCRIBE SELECT * FROM read_parquet(" + LakeSql.literal(specs) + ")", rs -> rs.getString(1))
                .contains("currency_profit")) return out;
        sql.query("SELECT symbol, currency_profit FROM read_parquet(" + LakeSql.literal(specs) + ") WHERE market = '"
                + market + "'", rs -> out.put(rs.getString(1), rs.getString(2)));
        return out;
    }

    private static String ts(LocalDateTime t) {
        return "TIMESTAMP '" + t.withNano(0).toString().replace('T', ' ') + "'";
    }

    private static LocalDateTime parse(String s) {
        return LocalDateTime.parse(s.replace(' ', 'T'));
    }
}
