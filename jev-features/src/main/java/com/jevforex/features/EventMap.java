package com.jevforex.features;

import com.jevforex.lake.LakeSql;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Locale;

/**
 * Mapa de eventos (pedido do operador em 20/10/2026): para cada tipo de divulgação (importância alta ou moderada, desde
 * 2022) e cada símbolo afetado — no forex os pares com a moeda; nos outros mercados a moeda de lucro (ouro, bitcoin,
 * petróleo e índices em USD, DE30 em EUR…) —, quanto o preço andou depois e para que lado, conforme a surpresa
 * (real × previsto).
 *
 * <pre>
 * ATR        = média da amplitude das 14 barras de 15 min antes da divulgação (o mesmo tamanho do ATR14 M15)
 * reação     = |preço 15/60 min depois − abertura do minuto da divulgação| ÷ ATR
 * depois de entrar = (preço 15/60 min depois − abertura 2 min depois) ÷ ATR, com o sinal (o que dá para operar)
 * surpresa   = real &gt; previsto (+), real &lt; previsto (−); sem previsto (discursos, decisões sem consenso) = 0
 * </pre>
 *
 * Descritivo, NÃO validado como regra: divulgações no mesmo horário dividem o mesmo movimento (CPI cheio, núcleo, mensal
 * e anual saem juntos), e direção "mais frequente" no passado não garante a próxima.
 */
public final class EventMap {

    /**
     * @param sameTime outras divulgações (alta/moderada, mesma moeda) no mesmo minuto, em média: o movimento é do conjunto
     * @param upPos15  fração em que subiu (de +2 a +15 min) quando o real veio ACIMA do previsto
     * @param movPos15 movimento médio com sinal (+2 a +15 min, em ATR) quando o real veio acima
     */
    public record Row(String eventCode, String name, String currency, String importance, String symbol, int n,
                      int nPos, int nNeg, Double abs15, Double abs60, Double upPos15, Double upNeg15, Double movPos15,
                      Double movNeg15, Double upPos60, Double upNeg60, Double movPos60, Double movNeg60, Double spreadAtr,
                      Double sameTime, Instant last) {
    }

    public record Report(String market, Instant builtAt, int events, int pairs, List<Row> rows, double seconds) {
    }

    private final Path lakeRoot;
    private final String market;
    private final List<String> symbols;

    /** @param symbols símbolos do mercado em uso (vazio = todos do silver) */
    public EventMap(Path lakeRoot, String market, List<String> symbols) {
        this.lakeRoot = lakeRoot;
        this.market = market;
        this.symbols = symbols == null ? List.of() : symbols;
    }

    public Report run(LakeSql sql) {
        long t0 = System.nanoTime();
        Path candles = lakeRoot.resolve("silver/candles_m1/market=" + market);
        Path events = lakeRoot.resolve("silver/calendar_events");
        Path defs = lakeRoot.resolve("silver/calendar_event_defs");
        Path specs = lakeRoot.resolve("silver/instrument_specs/part-0.parquet");
        if (!Files.isDirectory(candles) || !Files.isDirectory(events) || !Files.exists(specs)) {
            throw new IllegalStateException("Sem silver de candles/calendário de " + market + " (rode normalize)");
        }
        String symIn = symbols.isEmpty() ? "" : " AND symbol IN (" + String.join(",", symbols.stream()
                .map(s -> "'" + s.replace("'", "") + "'").toList()) + ")";
        sql.execute(String.format(Locale.ROOT, """
                CREATE OR REPLACE TEMP TABLE em1 AS
                SELECT symbol, time_utc AS t, open, high, low, spread_points
                  FROM read_parquet('%s/**/*.parquet', hive_partitioning = true)
                 WHERE time_utc >= TIMESTAMP '2022-01-01'%s
                """, LakeSql.slashes(candles), symIn));
        sql.execute("CREATE OR REPLACE TEMP TABLE em15 AS SELECT symbol, time_bucket(INTERVAL 15 MINUTE, t) AS t, "
                + "max(high) - min(low) AS rng FROM em1 GROUP BY ALL");
        sql.execute(String.format(Locale.ROOT, """
                CREATE OR REPLACE TEMP TABLE esy AS
                SELECT symbol, coalesce(point, 0.00001) AS point, currency_profit
                  FROM read_parquet(%s) WHERE market = '%s' AND symbol IN (SELECT DISTINCT symbol FROM em1)
                """, LakeSql.literal(specs), market));
        String defsSql = Files.isDirectory(defs)
                ? "SELECT DISTINCT ON (event_code) event_code, name FROM read_parquet('" + LakeSql.slashes(defs) + "/**/*.parquet', hive_partitioning = true)"
                : "SELECT NULL::VARCHAR AS event_code, NULL::VARCHAR AS name WHERE false";
        sql.execute(String.format(Locale.ROOT, """
                CREATE OR REPLACE TEMP TABLE eev AS
                WITH e AS (
                    SELECT value_id, event_code, currency, importance, scheduled_utc AS t0,
                           CASE WHEN actual IS NULL OR forecast IS NULL OR actual = forecast THEN 0
                                WHEN actual > forecast THEN 1 ELSE -1 END AS surprise
                      FROM read_parquet('%s/**/*.parquet', hive_partitioning = true)
                     WHERE importance IN ('HIGH', 'MODERATE') AND scheduled_utc >= TIMESTAMP '2022-01-01'
                       AND currency IN ('USD', 'EUR', 'GBP', 'JPY', 'CHF', 'AUD', 'CAD', 'NZD')
                       AND scheduled_utc < now() - INTERVAL 2 HOUR)
                SELECT e.*, d.name, count(*) OVER (PARTITION BY currency, t0) - 1 AS same_time
                  FROM e LEFT JOIN (%s) d USING (event_code)
                """, LakeSql.slashes(events), defsSql));
        // símbolos afetados: forex = pares com a moeda; os outros mercados = a moeda de lucro do símbolo
        sql.execute(String.format(Locale.ROOT, """
                CREATE OR REPLACE TEMP TABLE epr AS
                SELECT eev.*, esy.symbol, esy.point FROM eev JOIN esy
                    ON CASE WHEN '%s' = 'fx' THEN position(eev.currency IN esy.symbol) > 0 ELSE esy.currency_profit = eev.currency END
                """, market));
        sql.execute("""
                CREATE OR REPLACE TEMP TABLE emv AS
                WITH a AS (
                    SELECT epr.*, o0.open AS open0, o2.open AS open2, o2.spread_points AS spr
                      FROM epr
                      ASOF JOIN em1 o0 ON o0.symbol = epr.symbol AND epr.t0 <= o0.t
                      ASOF JOIN em1 o2 ON o2.symbol = epr.symbol AND epr.t0 + INTERVAL 2 MINUTE <= o2.t
                     WHERE o0.t - epr.t0 <= INTERVAL 2 MINUTE AND o2.t - epr.t0 <= INTERVAL 4 MINUTE),
                b AS (
                    SELECT a.*, c15.open AS px15 FROM a ASOF JOIN em1 c15 ON c15.symbol = a.symbol AND a.t0 + INTERVAL 15 MINUTE <= c15.t
                     WHERE c15.t - a.t0 <= INTERVAL 20 MINUTE),
                c AS (
                    SELECT b.*, c60.open AS px60 FROM b ASOF JOIN em1 c60 ON c60.symbol = b.symbol AND b.t0 + INTERVAL 60 MINUTE <= c60.t
                     WHERE c60.t - b.t0 <= INTERVAL 65 MINUTE),
                r AS (
                    SELECT c.symbol, c.value_id, avg(m.rng) AS atr FROM c JOIN em15 m ON m.symbol = c.symbol
                       AND m.t >= c.t0 - INTERVAL 210 MINUTE AND m.t < c.t0 GROUP BY ALL)
                SELECT c.event_code, c.name, c.currency, c.importance, c.symbol, c.surprise, c.t0, c.same_time,
                       abs(c.px15 - c.open0) / r.atr AS a15, abs(c.px60 - c.open0) / r.atr AS a60,
                       (c.px15 - c.open2) / r.atr AS m15, (c.px60 - c.open2) / r.atr AS m60,
                       c.spr * c.point / r.atr AS spread_atr
                  FROM c JOIN r USING (symbol, value_id) WHERE r.atr > 0
                """);
        List<Row> rows = sql.query("""
                SELECT event_code, any_value(name) AS name, any_value(currency) AS currency, any_value(importance) AS importance,
                       symbol, count(*) AS n,
                       count(*) FILTER (WHERE surprise > 0) AS npos, count(*) FILTER (WHERE surprise < 0) AS nneg,
                       avg(a15) AS abs15, avg(a60) AS abs60,
                       avg((m15 > 0)::INT) FILTER (WHERE surprise > 0) AS up_pos15, avg((m15 > 0)::INT) FILTER (WHERE surprise < 0) AS up_neg15,
                       avg(m15) FILTER (WHERE surprise > 0) AS mov_pos15, avg(m15) FILTER (WHERE surprise < 0) AS mov_neg15,
                       avg((m60 > 0)::INT) FILTER (WHERE surprise > 0) AS up_pos60, avg((m60 > 0)::INT) FILTER (WHERE surprise < 0) AS up_neg60,
                       avg(m60) FILTER (WHERE surprise > 0) AS mov_pos60, avg(m60) FILTER (WHERE surprise < 0) AS mov_neg60,
                       median(spread_atr) AS spread_atr, avg(same_time) AS same_time, max(t0) AS last
                  FROM emv GROUP BY event_code, symbol HAVING count(*) >= 8
                 ORDER BY abs15 DESC
                """, rs -> new Row(rs.getString("event_code"), rs.getString("name"), rs.getString("currency"),
                rs.getString("importance"), rs.getString("symbol"), rs.getInt("n"), rs.getInt("npos"), rs.getInt("nneg"),
                d(rs, "abs15"), d(rs, "abs60"), d(rs, "up_pos15"), d(rs, "up_neg15"), d(rs, "mov_pos15"), d(rs, "mov_neg15"),
                d(rs, "up_pos60"), d(rs, "up_neg60"), d(rs, "mov_pos60"), d(rs, "mov_neg60"), d(rs, "spread_atr"),
                d(rs, "same_time"), rs.getTimestamp("last").toLocalDateTime().toInstant(java.time.ZoneOffset.UTC)));
        int nEvents = (int) sql.scalar("SELECT count(DISTINCT value_id) FROM epr");
        int nPairs = (int) sql.scalar("SELECT count(*) FROM emv");
        return new Report(market, Instant.now(), nEvents, nPairs, rows, (System.nanoTime() - t0) / 1e9);
    }

    private static Double d(java.sql.ResultSet rs, String col) throws java.sql.SQLException {
        double v = rs.getDouble(col);
        return rs.wasNull() ? null : v;
    }
}
