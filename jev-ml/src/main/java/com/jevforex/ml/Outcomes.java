package com.jevforex.ml;

import com.jevforex.lake.LakeSql;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.Locale;

/**
 * Resultado de uma operação de verdade (documento mestre, capítulo 12), minuto a minuto no M1:
 *
 * <pre>
 * entrada  = ask (compra) / bid (venda) na abertura da primeira barra a partir de want_in
 * stop     = stopAtr × ATR da entrada        alvo = targetR × stop
 * saída    = o primeiro entre stop, alvo e o fim do horizonte (abertura da barra seguinte)
 * R        = resultado ÷ stop (com o spread de entrada e saída)
 * y        = resultado SÓ por tempo, em ATR (o mesmo do label do gold)
 * </pre>
 * Conservador: se stop e alvo cabem na mesma barra, conta o stop; stop com gap sai na abertura (pior que o stop);
 * alvo nunca sai melhor que o alvo. Candles são de bid: o lado do ask = bid + spread da barra × point.
 * Usado no experimento (momentos do gold) e no placar ao vivo (momentos das previsões).
 */
public final class Outcomes {

    private Outcomes() {
    }

    /** Barras M1 do silver e point por par (temp tables m1o e pto), uma vez por carga. */
    static void prepare(LakeSql sql, Path lakeRoot, String market, LocalDate from) {
        Path candles = lakeRoot.resolve("silver/candles_m1");
        if (!Files.isDirectory(candles)) throw new IllegalStateException("Sem silver de candles. Rode antes: normalize");
        sql.execute("""
                CREATE OR REPLACE TEMP TABLE m1o AS
                SELECT symbol, time_utc AS t, open, high, low, spread_points
                  FROM read_parquet('%s/**/*.parquet', hive_partitioning = true)
                 WHERE market = '%s' AND time_utc >= TIMESTAMP '%s 00:00:00'
                """.formatted(LakeSql.slashes(candles), market, from.minusDays(1)));
        preparePoints(sql, lakeRoot, market);
    }

    /**
     * Point por par (temp table pto) para as barras que já estão em m1o: o da corretora; sem ele, 0,001 (JPY) ou
     * 0,00001 (mesma regra do FeatureBuilder).
     */
    public static void preparePoints(LakeSql sql, Path lakeRoot, String market) {
        Path specs = lakeRoot.resolve("silver/instrument_specs/part-0.parquet");
        String specSql = Files.exists(specs)
                ? "SELECT symbol, point FROM read_parquet(" + LakeSql.literal(specs) + ") WHERE market = '" + market + "'"
                : "SELECT NULL::VARCHAR AS symbol, NULL::DOUBLE AS point WHERE false";
        String fallback = "fx".equals(market) ? "CASE WHEN right(m.symbol, 3) = 'JPY' THEN 0.001 ELSE 0.00001 END" : "0.01";
        sql.execute("""
                CREATE OR REPLACE TEMP TABLE pto AS
                SELECT m.symbol, coalesce(s.point, %s) AS point
                  FROM (SELECT DISTINCT symbol FROM m1o) m LEFT JOIN (%s) s ON s.symbol = m.symbol
                """.formatted(fallback, specSql));
    }

    /**
     * Momentos do gold de um horizonte: entrada na barra do label (+ atraso extra no teste de robustez).
     *
     * @param labels       pasta gold/labels do fset (dá o momento, a barra de entrada e o ATR)
     * @param extraMinutes atraso extra da entrada (0 = o do label; 5 = teste de robustez do capítulo 11)
     */
    static void create(LakeSql sql, Path labels, String name, int horizon, LocalDate from, double stopAtr,
                       double targetR, int extraMinutes) {
        createFrom(sql, """
                SELECT symbol, moment_utc, atr, entry_t + to_minutes(%d) AS want_in
                  FROM read_parquet('%s/**/*.parquet', hive_partitioning = true)
                 WHERE horizon_min = %d AND moment_utc >= TIMESTAMP '%s 00:00:00'
                """.formatted(extraMinutes, LakeSql.slashes(labels), horizon, from), name, horizon, stopAtr, targetR);
    }

    /**
     * Cria a temp table {@code name}(symbol, moment_utc, r_buy, r_sell, y_buy, y_sell) — precisa de m1o e pto.
     *
     * @param momentsSql SELECT com symbol, moment_utc, atr (preço) e want_in (a partir de quando entra)
     */
    public static void createFrom(LakeSql sql, String momentsSql, String name, int horizon, double stopAtr,
                                  double targetR) {
        if (!name.matches("[a-z_0-9]+")) throw new IllegalArgumentException("nome inválido: " + name);
        sql.execute(String.format(Locale.ROOT, """
                CREATE OR REPLACE TEMP TABLE %1$s AS
                WITH lab AS (%2$s),
                en AS (
                    SELECT lab.symbol, lab.moment_utc, lab.atr, p.point, e.t AS t_in,
                           e.open AS bid_in, e.open + e.spread_points * p.point AS ask_in
                      FROM lab JOIN pto p USING (symbol)
                      ASOF JOIN m1o e ON e.symbol = lab.symbol AND lab.want_in <= e.t
                     WHERE e.t - lab.want_in <= INTERVAL 5 MINUTE),
                lv AS (
                    SELECT *, ask_in - %4$f * atr AS b_stop, ask_in + %4$f * %5$f * atr AS b_tp,
                              bid_in + %4$f * atr AS s_stop, bid_in - %4$f * %5$f * atr AS s_tp,
                              t_in + to_minutes(%3$d) AS t_out
                      FROM en),
                path AS (
                    SELECT lv.symbol, lv.moment_utc,
                           arg_min(CASE WHEN b.low <= lv.b_stop THEN least(b.open, lv.b_stop) ELSE lv.b_tp END, b.t)
                               FILTER (WHERE b.low <= lv.b_stop OR b.high >= lv.b_tp) AS b_fill,
                           arg_min(CASE WHEN b.high + b.spread_points * lv.point >= lv.s_stop
                                        THEN greatest(b.open + b.spread_points * lv.point, lv.s_stop) ELSE lv.s_tp END, b.t)
                               FILTER (WHERE b.high + b.spread_points * lv.point >= lv.s_stop
                                          OR b.low + b.spread_points * lv.point <= lv.s_tp) AS s_fill
                      FROM lv JOIN m1o b ON b.symbol = lv.symbol AND b.t >= lv.t_in AND b.t < lv.t_out
                     GROUP BY lv.symbol, lv.moment_utc),
                ex AS (
                    SELECT lv.*, x.open AS bid_out, x.open + x.spread_points * lv.point AS ask_out
                      FROM lv ASOF JOIN m1o x ON x.symbol = lv.symbol AND lv.t_out <= x.t
                     WHERE x.t - lv.t_out <= INTERVAL 5 MINUTE)
                SELECT ex.symbol, ex.moment_utc,
                       (coalesce(pa.b_fill, ex.bid_out) - ex.ask_in) / (%4$f * ex.atr) AS r_buy,
                       (ex.bid_in - coalesce(pa.s_fill, ex.ask_out)) / (%4$f * ex.atr) AS r_sell,
                       (ex.bid_out - ex.ask_in) / ex.atr AS y_buy,
                       (ex.bid_in - ex.ask_out) / ex.atr AS y_sell
                  FROM ex LEFT JOIN path pa USING (symbol, moment_utc)
                """, name, momentsSql, horizon, stopAtr, targetR));
    }
}
