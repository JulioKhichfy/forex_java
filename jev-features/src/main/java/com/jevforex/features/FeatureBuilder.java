package com.jevforex.features;

import com.jevforex.lake.LakeSql;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Silver → gold (documento mestre, capítulos 10 e 11), fset v1, mercado fx.
 *
 * <pre>
 * gold/features/market=fx/fset=v1/year=…   1 linha por (momento de decisão, par): grupos A (preço, custo,
 *                                          fator USD) e B (surpresa do calendário)
 * gold/labels/market=fx/fset=v1/horizon=…  resultado depois de h minutos, já pagando o spread
 * </pre>
 *
 * <p><b>Point-in-time:</b> toda feature no momento t usa só barras que FECHARAM até t
 * (barra M1 que abre em b fecha em b+1 min; M15 em b+15 min; H1 em b+1 h) e só divulgações com
 * actual_available_utc ≤ t. O σ da surpresa usa só divulgações anteriores do mesmo evento.</p>
 *
 * <p><b>Labels:</b> entra no preço de abertura da primeira barra em t + atraso (o M1 não tem preço no
 * meio do minuto; 60 s é mais conservador que os 30 s do cap. 11) e sai h minutos depois. O MT5 dá
 * candles de bid: ask = bid + spread da barra × point. y_compra = (bid_saída − ask_entrada) / ATR;
 * y_venda = (bid_entrada − ask_saída) / ATR.</p>
 *
 * <p>Adaptações de cálculo (versão v1): ATR e RSI com média simples de 14 barras M15 (não Wilder);
 * média de 50 barras H1 no lugar da EMA50.</p>
 */
public final class FeatureBuilder {

    private static final int STALE_MINUTES = 5;       // última barra mais velha que isto = mercado fechado
    private static final int MATCH_MINUTES = 5;       // barra de entrada/saída até 5 min depois do alvo

    private final Path lakeRoot;
    private final FeatureConfig cfg;

    public FeatureBuilder(Path lakeRoot, FeatureConfig cfg) {
        this.lakeRoot = lakeRoot;
        this.cfg = cfg;
    }

    public record SymbolCounts(String symbol, long eventMoments, long controlMoments, String firstMoment,
                               String lastMoment) {
    }

    /** @param meanCostAtr custo médio de uma operação (um spread) em ATR */
    public record LabelStats(int horizonMinutes, long rows, long up, long down, long flat, double meanCostAtr) {
    }

    /**
     * @param textSignals   documentos com sinal do Jev disponíveis para o grupo C (0 = grupo C zerado)
     * @param textZeroShare fração dos momentos sem nenhum sinal de texto nas últimas ~18 dias (τ longo × 6)
     */
    public record Report(String fset, long moments, long eventMoments, long controlMoments, long releases,
                         long releasesWithZ, long textSignals, double textZeroShare, List<String> symbolsWithoutSpec,
                         List<SymbolCounts> symbols, List<LabelStats> labels, Map<String, Double> nullShare,
                         Path featuresOut, Path labelsOut) {
    }

    public Report run(LakeSql sql) {
        Path silver = lakeRoot.resolve("silver");
        Path featuresOut = lakeRoot.resolve("gold/features/market=fx/fset=" + cfg.fset());
        Path labelsOut = lakeRoot.resolve("gold/labels/market=fx/fset=" + cfg.fset());
        Path candles = silver.resolve("candles_m1");
        if (!Files.isDirectory(candles)) {
            throw new IllegalStateException("Sem silver de candles em " + candles + ". Rode antes: normalize");
        }

        // ---------------------------------------------------------------- entradas
        sql.execute("""
                CREATE OR REPLACE TEMP TABLE m1 AS
                SELECT symbol, time_utc AS t, time_utc + INTERVAL 1 MINUTE AS close_time,
                       open, high, low, close, spread_points
                  FROM read_parquet('%s/**/*.parquet', hive_partitioning = true)
                 WHERE market = 'fx'
                """.formatted(LakeSql.slashes(candles)));
        Path specs = silver.resolve("instrument_specs/part-0.parquet");
        String specSql = Files.exists(specs)
                ? "SELECT symbol, point FROM read_parquet(" + LakeSql.literal(specs) + ") WHERE market = 'fx'"
                : "SELECT NULL::VARCHAR AS symbol, NULL::DOUBLE AS point WHERE false";
        sql.execute("""
                CREATE OR REPLACE TEMP TABLE pairs AS
                SELECT m.symbol, left(m.symbol, 3) AS base, right(m.symbol, 3) AS quote,
                       CASE WHEN right(m.symbol, 3) = 'USD' THEN -1 WHEN left(m.symbol, 3) = 'USD' THEN 1 ELSE 0 END
                           AS usd_sign,
                       s.point AS spec_point,
                       coalesce(s.point, CASE WHEN right(m.symbol, 3) = 'JPY' THEN 0.001 ELSE 0.00001 END) AS point
                  FROM (SELECT DISTINCT symbol FROM m1) m
                  LEFT JOIN (%s) s ON s.symbol = m.symbol
                """.formatted(specSql));
        List<String> withoutSpec = sql.query("SELECT symbol FROM pairs WHERE spec_point IS NULL ORDER BY symbol",
                rs -> rs.getString(1));
        sql.execute("CREATE OR REPLACE TEMP TABLE bounds AS SELECT symbol, min(close_time) AS first_close, "
                + "max(close_time) AS last_close FROM m1 GROUP BY symbol");

        // ---------------------------------------------------------------- M15: ATR, RSI, faixa do dia, regime
        sql.execute("""
                CREATE OR REPLACE TEMP TABLE m15_raw AS
                WITH b AS (
                    SELECT symbol, time_bucket(INTERVAL 15 MINUTE, t) AS b,
                           max(high) AS h, min(low) AS l, arg_max(close, t) AS c
                      FROM m1 GROUP BY symbol, b),
                d AS (
                    SELECT *, greatest(h - l, abs(h - lag(c) OVER w), abs(l - lag(c) OVER w)) AS tr,
                           c - lag(c) OVER w AS dc
                      FROM b WINDOW w AS (PARTITION BY symbol ORDER BY b))
                SELECT symbol, b + INTERVAL 15 MINUTE AS close_time, c,
                       CASE WHEN count(tr) OVER w14 = 14 THEN avg(tr) OVER w14 END AS atr,
                       CASE WHEN count(dc) OVER w14 = 14 THEN avg(greatest(dc, 0)) OVER w14 END AS avg_gain,
                       CASE WHEN count(dc) OVER w14 = 14 THEN avg(greatest(-dc, 0)) OVER w14 END AS avg_loss,
                       max(h) OVER wd AS day_hi, min(l) OVER wd AS day_lo
                  FROM d
                WINDOW w14 AS (PARTITION BY symbol ORDER BY b ROWS 13 PRECEDING),
                       wd AS (PARTITION BY symbol, CAST(b AS DATE) ORDER BY b ROWS UNBOUNDED PRECEDING)
                """);
        sql.execute("""
                CREATE OR REPLACE TEMP TABLE m15 AS
                SELECT symbol, close_time, atr, day_hi, day_lo,
                       CASE WHEN avg_loss = 0 THEN 100 ELSE 100 - 100 / (1 + avg_gain / avg_loss) END AS rsi,
                       CASE WHEN count(atr) OVER w20 >= 1900 THEN atr / nullif(median(atr) OVER w20, 0) END AS regime
                  FROM m15_raw
                WINDOW w20 AS (PARTITION BY symbol ORDER BY close_time ROWS BETWEEN 1919 PRECEDING AND CURRENT ROW)
                """);

        // ---------------------------------------------------------------- H1: média de 50 barras
        sql.execute("""
                CREATE OR REPLACE TEMP TABLE h1 AS
                WITH b AS (SELECT symbol, time_bucket(INTERVAL 1 HOUR, t) AS b, arg_max(close, t) AS c
                             FROM m1 GROUP BY ALL)
                SELECT symbol, b + INTERVAL 1 HOUR AS close_time,
                       CASE WHEN count(c) OVER w = 50 THEN avg(c) OVER w END AS sma50
                  FROM b WINDOW w AS (PARTITION BY symbol ORDER BY b ROWS 49 PRECEDING)
                """);

        // ---------------------------------------------------------------- spread típico do horário (20 dias ANTERIORES)
        sql.execute("""
                CREATE OR REPLACE TEMP TABLE spread_base AS
                WITH h AS (SELECT symbol, CAST(t AS DATE) AS d, hour(t) AS hr, median(spread_points) AS med
                             FROM m1 GROUP BY ALL)
                SELECT symbol, d, hr,
                       CASE WHEN count(med) OVER w >= 10 THEN median(med) OVER w END AS base
                  FROM h WINDOW w AS (PARTITION BY symbol, hr ORDER BY d ROWS BETWEEN 20 PRECEDING AND 1 PRECEDING)
                """);

        // ---------------------------------------------------------------- calendário: divulgações e surpresa z
        Path calendar = silver.resolve("calendar_events");
        String calendarSql = Files.isDirectory(calendar)
                ? "SELECT * FROM read_parquet('" + LakeSql.slashes(calendar) + "/**/*.parquet', hive_partitioning = true)"
                : "SELECT NULL::UBIGINT AS value_id, NULL::UBIGINT AS event_id, NULL::VARCHAR AS event_code, "
                + "NULL::VARCHAR AS currency, NULL::VARCHAR AS importance, NULL::TIMESTAMP AS scheduled_utc, "
                + "NULL::DOUBLE AS actual, NULL::DOUBLE AS forecast, NULL::TIMESTAMP AS actual_available_utc WHERE false";
        sql.execute("CREATE OR REPLACE TEMP TABLE cal_states AS " + calendarSql);
        sql.execute("""
                CREATE OR REPLACE TEMP TABLE releases AS
                WITH r AS (
                    SELECT value_id, any_value(event_id) AS event_id, any_value(event_code) AS event_code,
                           any_value(currency) AS currency, any_value(importance) AS importance,
                           arg_min(scheduled_utc, actual_available_utc) AS scheduled_utc,
                           arg_min(actual, actual_available_utc) AS actual,
                           arg_min(forecast, actual_available_utc) AS forecast,
                           min(actual_available_utc) AS available_utc
                      FROM cal_states WHERE actual IS NOT NULL GROUP BY value_id),
                s AS (
                    SELECT *, actual - forecast AS diff,
                           stddev_samp(actual - forecast) OVER w AS sigma,
                           count(actual - forecast) OVER w AS n_hist
                      FROM r WINDOW w AS (PARTITION BY event_id ORDER BY scheduled_utc
                                          ROWS BETWEEN %d PRECEDING AND 1 PRECEDING))
                SELECT *,
                       CASE WHEN n_hist >= %d AND sigma > 0 AND diff IS NOT NULL
                            THEN greatest(-4.0, least(4.0, %s * diff / sigma)) END AS z,
                       %s AS weight
                  FROM s
                """.formatted(cfg.sigmaWindow(), cfg.sigmaMinHistory(), polarity(), weight("importance")));
        sql.execute("""
                CREATE OR REPLACE TEMP TABLE upcoming AS
                SELECT DISTINCT value_id, currency, scheduled_utc FROM cal_states WHERE %s > 0
                """.formatted(weight("importance")));

        // ---------------------------------------------------------------- momentos de decisão
        String hours = cfg.controlHoursUtc().isEmpty() ? "-1"
                : cfg.controlHoursUtc().stream().map(String::valueOf).collect(Collectors.joining(","));
        List<String> span = sql.query("SELECT CAST(date_trunc('hour', min(first_close)) AS VARCHAR), "
                + "CAST(max(last_close) AS VARCHAR) FROM bounds", rs -> rs.getString(1) + "|" + rs.getString(2));
        String[] ends = span.get(0).split("\\|");
        sql.execute("""
                CREATE OR REPLACE TEMP TABLE moments AS
                WITH ev AS (
                    SELECT DISTINCT p.symbol,
                           time_bucket(INTERVAL 1 MINUTE, r.scheduled_utc) + to_minutes(%d) AS t, 'EVENT' AS kind
                      FROM releases r JOIN pairs p ON r.currency IN (p.base, p.quote)
                     WHERE r.weight > 0),
                ctrl AS (
                    SELECT p.symbol, g.t, 'CONTROL' AS kind
                      FROM generate_series(TIMESTAMP '%s', TIMESTAMP '%s', INTERVAL 1 HOUR) AS g(t), pairs p
                     WHERE isodow(g.t) BETWEEN 1 AND 5 AND hour(g.t) IN (%s)
                       AND NOT (isodow(g.t) = 5 AND hour(g.t) >= 19)),
                u AS (SELECT symbol, t, max(kind) AS kind
                        FROM (SELECT * FROM ev UNION ALL SELECT * FROM ctrl) GROUP BY symbol, t)
                SELECT u.symbol, u.t, u.kind,
                       u.t - INTERVAL 15 MINUTE AS t15, u.t - INTERVAL 60 MINUTE AS t60,
                       u.t - INTERVAL 240 MINUTE AS t240
                  FROM u JOIN bounds b USING (symbol)
                 WHERE u.t BETWEEN b.first_close AND b.last_close
                """.formatted(cfg.eventLagMinutes(), ends[0], ends[1], hours));

        // ---------------------------------------------------------------- fator USD (média nas 7 moedas)
        sql.execute("""
                CREATE OR REPLACE TEMP TABLE usd AS
                WITH x AS (SELECT DISTINCT t, t15, t60 FROM moments),
                r AS (
                    SELECT x.t, p.usd_sign, a0.close_time AS ct0,
                           ln(a0.close / a15.close) AS r15, ln(a0.close / a60.close) AS r60
                      FROM x CROSS JOIN pairs p
                      ASOF JOIN m1 a0 ON a0.symbol = p.symbol AND x.t >= a0.close_time
                      ASOF JOIN m1 a15 ON a15.symbol = p.symbol AND x.t15 >= a15.close_time
                      ASOF JOIN m1 a60 ON a60.symbol = p.symbol AND x.t60 >= a60.close_time
                     WHERE p.usd_sign <> 0)
                SELECT t, avg(usd_sign * r15) AS usd15, avg(usd_sign * r60) AS usd60, count(*) AS n_pairs
                  FROM r WHERE t - ct0 <= to_minutes(%d) GROUP BY t
                """.formatted(STALE_MINUTES));

        // ---------------------------------------------------------------- surpresa por moeda com decaimento
        sql.execute(String.format(Locale.ROOT, """
                CREATE OR REPLACE TEMP TABLE surprise AS
                SELECT m.symbol, m.t,
                       sum(CASE WHEN r.currency = p.base
                                THEN r.weight * r.z * exp(-date_diff('second', r.available_utc, m.t) / %1$f) END)
                           AS s_base,
                       sum(CASE WHEN r.currency = p.quote
                                THEN r.weight * r.z * exp(-date_diff('second', r.available_utc, m.t) / %1$f) END)
                           AS s_quote
                  FROM moments m JOIN pairs p USING (symbol)
                  JOIN releases r ON r.currency IN (p.base, p.quote) AND r.z IS NOT NULL AND r.weight > 0
                                 AND r.available_utc <= m.t AND r.available_utc > m.t - to_minutes(%2$d)
                 GROUP BY m.symbol, m.t
                """, cfg.surpriseDecayMinutes() * 60.0, cfg.surpriseDecayMinutes() * 6));
        sql.execute("CREATE OR REPLACE TEMP TABLE ev_avail AS SELECT currency, available_utc FROM releases WHERE weight > 0");

        // ---------------------------------------------------------------- grupo C: sinais do Jev por moeda
        long textSignals = buildTextFeatures(sql);

        // ---------------------------------------------------------------- features
        sql.execute("""
                CREATE OR REPLACE TEMP TABLE feats AS
                WITH j AS (
                    SELECT m.symbol, m.t, m.kind, p.base, p.quote, p.usd_sign, p.point,
                           a0.close, a0.close_time AS last_bar_close, a0.spread_points,
                           a15.close AS close15, a60.close AS close60, a240.close AS close240,
                           q.atr, q.rsi, q.regime, q.day_hi, q.day_lo, hh.sma50,
                           lb.available_utc AS last_base, lq.available_utc AS last_quote,
                           nb.scheduled_utc AS next_base, nq.scheduled_utc AS next_quote
                      FROM moments m JOIN pairs p USING (symbol)
                      ASOF JOIN m1 a0 ON a0.symbol = m.symbol AND m.t >= a0.close_time
                      ASOF JOIN m1 a15 ON a15.symbol = m.symbol AND m.t15 >= a15.close_time
                      ASOF JOIN m1 a60 ON a60.symbol = m.symbol AND m.t60 >= a60.close_time
                      ASOF JOIN m1 a240 ON a240.symbol = m.symbol AND m.t240 >= a240.close_time
                      ASOF JOIN m15 q ON q.symbol = m.symbol AND m.t >= q.close_time
                      ASOF JOIN h1 hh ON hh.symbol = m.symbol AND m.t >= hh.close_time
                      ASOF LEFT JOIN ev_avail lb ON lb.currency = p.base AND m.t >= lb.available_utc
                      ASOF LEFT JOIN ev_avail lq ON lq.currency = p.quote AND m.t >= lq.available_utc
                      ASOF LEFT JOIN upcoming nb ON nb.currency = p.base AND m.t < nb.scheduled_utc
                      ASOF LEFT JOIN upcoming nq ON nq.currency = p.quote AND m.t < nq.scheduled_utc)
                SELECT j.symbol, j.t AS moment_utc, j.kind, j.base, j.quote,
                       j.atr, j.atr / j.close * 1e4 AS atr_bps,
                       (j.close - j.close15) / j.atr AS ret15_atr,
                       (j.close - j.close60) / j.atr AS ret60_atr,
                       (j.close - j.close240) / j.atr AS ret240_atr,
                       (j.close - j.sma50) / j.atr AS dist_sma50_h1_atr,
                       j.rsi AS rsi14_m15,
                       (j.day_hi - j.day_lo) / j.atr AS day_range_atr,
                       j.regime,
                       j.spread_points * j.point / j.atr AS spread_atr,
                       j.spread_points / nullif(sb.base, 0) AS spread_rel,
                       sin(2 * pi() * (hour(j.t) + minute(j.t) / 60.0) / 24) AS hour_sin,
                       cos(2 * pi() * (hour(j.t) + minute(j.t) / 60.0) / 24) AS hour_cos,
                       isodow(j.t) AS dow,
                       CAST(hour(j.t) < 9 AS INTEGER) AS sess_tokyo,
                       CAST(hour(j.t) BETWEEN 7 AND 15 AS INTEGER) AS sess_london,
                       CAST(hour(j.t) BETWEEN 12 AND 20 AS INTEGER) AS sess_ny,
                       u.usd15 * 1e4 AS usd_factor15_bps, u.usd60 * 1e4 AS usd_factor60_bps,
                       (ln(j.close / j.close15) - j.usd_sign * u.usd15) * 1e4 AS resid15_bps,
                       (ln(j.close / j.close60) - j.usd_sign * u.usd60) * 1e4 AS resid60_bps,
                       coalesce(s.s_base, 0) AS surprise_base, coalesce(s.s_quote, 0) AS surprise_quote,
                       coalesce(s.s_base, 0) - coalesce(s.s_quote, 0) AS surprise_diff,
                       least(1440, date_diff('minute', list_max([j.last_base, j.last_quote]), j.t)) AS min_since_event,
                       least(1440, date_diff('minute', j.t, list_min([j.next_base, j.next_quote]))) AS min_to_event,
                       coalesce(tx.ts_base, 0) AS text_short_base, coalesce(tx.ts_quote, 0) AS text_short_quote,
                       coalesce(tx.ts_base, 0) - coalesce(tx.ts_quote, 0) AS text_short_diff,
                       coalesce(tx.tl_base, 0) AS text_long_base, coalesce(tx.tl_quote, 0) AS text_long_quote,
                       coalesce(tx.tl_base, 0) - coalesce(tx.tl_quote, 0) AS text_long_diff,
                       coalesce(tx.guid_base, 0) AS guidance_base, coalesce(tx.guid_quote, 0) AS guidance_quote,
                       coalesce(tx.docs_24h, 0) AS text_docs_24h,
                       coalesce(least(168, date_diff('hour', tx.last_text, j.t)), 168) AS hours_since_text,
                       year(j.t) AS year
                  FROM j
                  LEFT JOIN spread_base sb ON sb.symbol = j.symbol AND sb.d = CAST(j.t AS DATE) AND sb.hr = hour(j.t)
                  LEFT JOIN usd u ON u.t = j.t
                  LEFT JOIN surprise s ON s.symbol = j.symbol AND s.t = j.t
                  LEFT JOIN text_feat tx ON tx.symbol = j.symbol AND tx.t = j.t
                 WHERE j.t - j.last_bar_close <= to_minutes(%d)
                   AND j.atr > 0 AND j.regime IS NOT NULL AND j.sma50 IS NOT NULL
                """.formatted(STALE_MINUTES));

        // ---------------------------------------------------------------- labels
        String horizons = cfg.horizonsMinutes().stream().map(h -> "(" + h + ")").collect(Collectors.joining(","));
        sql.execute("""
                CREATE OR REPLACE TEMP TABLE labels AS
                WITH want AS (
                    SELECT f.symbol, f.moment_utc, f.atr, p.point, f.moment_utc + to_minutes(%1$d) AS want_in
                      FROM feats f JOIN pairs p USING (symbol)),
                entry AS (
                    SELECT w.*, e.t AS entry_t, e.open AS bid_in, e.open + e.spread_points * w.point AS ask_in
                      FROM want w ASOF JOIN m1 e ON e.symbol = w.symbol AND w.want_in <= e.t
                     WHERE e.t - w.want_in <= to_minutes(%2$d)),
                wx AS (SELECT entry.*, h.horizon, entry.entry_t + to_minutes(h.horizon) AS want_out
                         FROM entry CROSS JOIN (VALUES %3$s) AS h(horizon))
                SELECT wx.symbol, wx.moment_utc, CAST(wx.horizon AS VARCHAR) || 'm' AS horizon, wx.horizon AS horizon_min,
                       wx.entry_t, wx.bid_in, wx.ask_in, x.t AS exit_t, x.open AS bid_out,
                       x.open + x.spread_points * wx.point AS ask_out, wx.atr,
                       (x.open - wx.ask_in) / wx.atr AS y_buy,
                       (wx.bid_in - (x.open + x.spread_points * wx.point)) / wx.atr AS y_sell,
                       -- custo de UMA operação (a compra paga o spread da entrada; a venda, o da saída): média dos dois
                       ((wx.ask_in - wx.bid_in) + x.spread_points * wx.point) / 2 / wx.atr AS cost_atr,
                       x.t + INTERVAL 1 MINUTE AS label_available_utc
                  FROM wx ASOF JOIN m1 x ON x.symbol = wx.symbol AND wx.want_out <= x.t
                 WHERE x.t - wx.want_out <= to_minutes(%2$d)
                """.formatted(cfg.entryDelayMinutes(), MATCH_MINUTES, horizons));
        sql.execute(String.format(Locale.ROOT, """
                CREATE OR REPLACE TEMP TABLE labels_final AS
                SELECT *, CASE WHEN y_buy > %1$f THEN 'ALTA' WHEN y_sell > %1$f THEN 'QUEDA' ELSE 'LATERAL' END AS label
                  FROM labels
                """, cfg.labelThresholdAtr()));

        // ---------------------------------------------------------------- gravação (troca só quando completo)
        write(sql, "SELECT * FROM feats ORDER BY moment_utc, symbol", "year", featuresOut);
        write(sql, "SELECT * FROM labels_final ORDER BY moment_utc, symbol", "horizon", labelsOut);

        // ---------------------------------------------------------------- relatório
        long moments = sql.scalar("SELECT count(*) FROM feats");
        long events = sql.scalar("SELECT count(*) FROM feats WHERE kind = 'EVENT'");
        List<SymbolCounts> bySymbol = sql.query("""
                SELECT symbol, count(*) FILTER (WHERE kind = 'EVENT'), count(*) FILTER (WHERE kind = 'CONTROL'),
                       CAST(min(moment_utc) AS VARCHAR), CAST(max(moment_utc) AS VARCHAR)
                  FROM feats GROUP BY symbol ORDER BY symbol
                """, rs -> new SymbolCounts(rs.getString(1), rs.getLong(2), rs.getLong(3), rs.getString(4),
                rs.getString(5)));
        List<LabelStats> labels = sql.query("""
                SELECT horizon_min, count(*), count(*) FILTER (WHERE label = 'ALTA'),
                       count(*) FILTER (WHERE label = 'QUEDA'), count(*) FILTER (WHERE label = 'LATERAL'),
                       avg(cost_atr)
                  FROM labels_final GROUP BY horizon_min ORDER BY horizon_min
                """, rs -> new LabelStats(rs.getInt(1), rs.getLong(2), rs.getLong(3), rs.getLong(4), rs.getLong(5),
                rs.getDouble(6)));
        Map<String, Double> nulls = new LinkedHashMap<>();
        for (String col : List.of("ret240_atr", "spread_rel", "usd_factor60_bps", "min_since_event", "min_to_event")) {
            nulls.put(col, moments == 0 ? 0 : sql.query("SELECT avg(CASE WHEN " + col + " IS NULL THEN 1.0 ELSE 0 END) "
                    + "FROM feats", rs -> rs.getDouble(1)).get(0));
        }
        long releases = sql.scalar("SELECT count(*) FROM releases");
        long withZ = sql.scalar("SELECT count(*) FROM releases WHERE z IS NOT NULL");
        double textZero = moments == 0 ? 0 : sql.query("SELECT avg(CASE WHEN text_long_base = 0 AND "
                + "text_long_quote = 0 THEN 1.0 ELSE 0 END) FROM feats", rs -> rs.getDouble(1)).get(0);
        return new Report(cfg.fset(), moments, events, moments - events, releases, withZ, textSignals, textZero,
                withoutSpec, bySymbol, labels, nulls, featuresOut, labelsOut);
    }

    /**
     * Grupo C (cap. 10): por moeda, S(t) = Σ sinal × exp(−Δ/τ) dos documentos com available_utc ≤ t, com τ curto
     * (6 h) e longo (72 h); guidance = Σ P(mudança de orientação) × relevância × exp(−Δ/72 h); quantidade de
     * documentos relevantes nas últimas 24 h e horas desde o último. Sem sinais no gold: tudo zero.
     *
     * @return quantos documentos com sinal entraram
     */
    private long buildTextFeatures(LakeSql sql) {
        FeatureConfig.Text t = cfg.text();
        Path signals = t == null ? null : lakeRoot.resolve(t.signalsPath());
        if (signals == null || !Files.isDirectory(signals)) {
            sql.execute("CREATE OR REPLACE TEMP TABLE text_sig AS SELECT NULL::VARCHAR AS currency, "
                    + "NULL::TIMESTAMP AS available_utc, NULL::DOUBLE AS signal, NULL::DOUBLE AS relevance, "
                    + "NULL::DOUBLE AS guidance WHERE false");
        } else {
            sql.execute("""
                    CREATE OR REPLACE TEMP TABLE text_sig AS
                    SELECT currency, available_utc, signal, relevance, coalesce(guidance_change, 0) AS guidance
                      FROM read_parquet('%s/**/*.parquet', hive_partitioning = true)
                    """.formatted(LakeSql.slashes(signals)));
        }
        double shortS = (t == null ? 6 : t.tauShortHours()) * 3600, longS = (t == null ? 72 : t.tauLongHours()) * 3600;
        double minRel = t == null ? 0.5 : t.minRelevance();
        // 6τ: depois disso o peso é < 0,25% e o documento sai da soma
        sql.execute(String.format(Locale.ROOT, """
                CREATE OR REPLACE TEMP TABLE text_feat AS
                WITH x AS (
                    SELECT m.symbol, m.t, s.currency = p.base AS is_base, s.signal, s.relevance, s.guidance,
                           s.available_utc, date_diff('second', s.available_utc, m.t) AS age
                      FROM moments m JOIN pairs p USING (symbol)
                      JOIN text_sig s ON s.currency IN (p.base, p.quote)
                                     AND s.available_utc <= m.t
                                     AND s.available_utc > m.t - to_seconds(%2$d))
                SELECT symbol, t,
                       sum(CASE WHEN is_base AND age < %1$f * 6 THEN signal * exp(-age / %1$f) END) AS ts_base,
                       sum(CASE WHEN NOT is_base AND age < %1$f * 6 THEN signal * exp(-age / %1$f) END) AS ts_quote,
                       sum(CASE WHEN is_base THEN signal * exp(-age / %3$f) END) AS tl_base,
                       sum(CASE WHEN NOT is_base THEN signal * exp(-age / %3$f) END) AS tl_quote,
                       sum(CASE WHEN is_base THEN guidance * relevance * exp(-age / %3$f) END) AS guid_base,
                       sum(CASE WHEN NOT is_base THEN guidance * relevance * exp(-age / %3$f) END) AS guid_quote,
                       count(*) FILTER (WHERE relevance >= %4$f AND age < 86400) AS docs_24h,
                       max(available_utc) FILTER (WHERE relevance >= %4$f) AS last_text
                  FROM x GROUP BY symbol, t
                """, shortS, (long) (longS * 6), longS, minRel));
        return sql.scalar("SELECT count(*) FROM text_sig");
    }

    private static void write(LakeSql sql, String select, String partition, Path out) {
        Path tmp = out.resolveSibling(out.getFileName() + ".tmp");
        LakeSql.deleteRecursively(tmp);
        out.getParent().toFile().mkdirs();
        if (sql.scalar("SELECT count(*) FROM (" + select + ")") == 0) {
            LakeSql.deleteRecursively(out);
            return;
        }
        sql.execute("COPY (" + select + ") TO " + LakeSql.literal(tmp)
                + " (FORMAT PARQUET, COMPRESSION ZSTD, PARTITION_BY (" + partition + "))");
        LakeSql.replaceDirectory(tmp, out);
    }

    /** +1, ou −1 quando o event_code contém um dos trechos de polaridade negativa (desemprego, pedidos…). */
    private String polarity() {
        if (cfg.negativePolarity().isEmpty()) return "1";
        String alternation = cfg.negativePolarity().stream()
                .map(s -> s.toLowerCase().replaceAll("[^a-z0-9-]", ""))
                .filter(s -> !s.isEmpty())
                .collect(Collectors.joining("|"));
        return "(CASE WHEN regexp_matches(lower(event_code), '(" + alternation + ")') THEN -1 ELSE 1 END)";
    }

    private String weight(String importanceColumn) {
        StringBuilder sb = new StringBuilder("(CASE " + importanceColumn);
        cfg.importanceWeights().forEach((imp, w) -> sb.append(" WHEN '").append(imp.toUpperCase().replace("'", ""))
                .append("' THEN ").append(String.format(Locale.ROOT, "%.6f", w)));
        return sb.append(" ELSE 0 END)").toString();
    }
}
