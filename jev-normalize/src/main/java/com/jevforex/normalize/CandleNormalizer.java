package com.jevforex.normalize;

import com.jevforex.lake.LakeSql;

import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * bronze/mt5_candles → silver/candles_m1/market=…/symbol=…/year=…/month=…/*.parquet
 *
 * <p>Uma linha por (mercado, símbolo, minuto UTC). A mesma barra pode vir em mais de um arquivo
 * (histórico + recuperação + ao vivo): fica a do arquivo visto primeiro. O silver é reconstruído
 * inteiro a partir do bronze a cada execução (regra 2 do lake).</p>
 *
 * <p>Colunas: market, symbol, broker_symbol, time_utc (abertura da barra; ela fecha 60 s depois),
 * open, high, low, close, tick_volume, spread_points, real_volume, origin, seen_utc.</p>
 */
public final class CandleNormalizer {

    public static final String SOURCE = "mt5_candles";
    public static final String TABLE = "candles_m1";

    private final Path lakeRoot;

    public CandleNormalizer(Path lakeRoot) {
        this.lakeRoot = lakeRoot;
    }

    /**
     * @param intradayGaps    buracos de 10 min a 6 h entre barras (dentro do pregão)
     * @param maxIntradayGap  maior buraco intradiário, em minutos
     */
    public record SymbolStats(String market, String symbol, long bars, long duplicatesDropped, LocalDateTime first,
                              LocalDateTime last, long intradayGaps, long maxIntradayGap,
                              LocalDateTime maxIntradayGapAfter) {
    }

    /**
     * @param rowsBeforeReliable barras anteriores a utcReliableFrom, deixadas de fora do silver
     */
    public record Report(long files, long rowsRead, long rowsWritten, long rowsWithoutMeta,
                         LocalDate utcReliableFrom, long rowsBeforeReliable, List<SymbolStats> symbols,
                         WeeklyOpenCheck.Result weeklyOpen, Path output) {
    }

    /**
     * @param utcReliableFrom barras antes desta data (UTC) não entram no silver: o histórico antigo da
     *                        corretora pode estar gravado em outro fuso (a checagem de abertura semanal mostra).
     *                        O bronze continua intacto. null = sem corte.
     */
    /**
     * Modo ao vivo: cria a temp table {@code table} (o mesmo formato do silver) só com os arquivos do bronze
     * gravados a partir de {@code since}. Sem arquivos: tabela vazia.
     *
     * @return quantos arquivos entraram
     */
    public static int createRecent(LakeSql sql, Path lakeRoot, java.time.Instant since, String table) {
        List<Path> csv = Bronze.recentCsv(lakeRoot, SOURCE, since);
        List<Path> meta = Bronze.metaOf(csv);
        if (csv.isEmpty() || meta.isEmpty()) {
            sql.execute("CREATE OR REPLACE TEMP TABLE " + table + " AS SELECT NULL::VARCHAR AS market, "
                    + "NULL::VARCHAR AS symbol, NULL::VARCHAR AS broker_symbol, NULL::TIMESTAMP AS time_utc, "
                    + "NULL::DOUBLE AS open, NULL::DOUBLE AS high, NULL::DOUBLE AS low, NULL::DOUBLE AS close, "
                    + "NULL::BIGINT AS tick_volume, NULL::INTEGER AS spread_points, NULL::BIGINT AS real_volume, "
                    + "NULL::VARCHAR AS origin, NULL::TIMESTAMP AS seen_utc WHERE false");
            return 0;
        }
        Bronze.createMetaTable(sql, Bronze.list(meta), table + "_meta");
        readRaw(sql, Bronze.list(csv), table + "_raw");
        dedup(sql, table + "_raw", table + "_meta", "TIMESTAMP '1970-01-01'", table);
        return csv.size();
    }

    private static void readRaw(LakeSql sql, String files, String table) {
        sql.execute("""
                CREATE OR REPLACE TEMP TABLE %s AS
                SELECT market, symbol, broker_symbol, time_utc, open, high, low, close, tick_volume,
                       spread_points, real_volume, %s AS sha
                  FROM read_csv(%s, skip = 1, header = true, delim = ';', filename = true, auto_detect = false,
                                columns = {'market': 'VARCHAR', 'symbol': 'VARCHAR', 'broker_symbol': 'VARCHAR',
                                           'time_server': 'VARCHAR', 'time_utc': 'VARCHAR', 'open': 'DOUBLE',
                                           'high': 'DOUBLE', 'low': 'DOUBLE', 'close': 'DOUBLE',
                                           'tick_volume': 'BIGINT', 'spread_points': 'INTEGER',
                                           'real_volume': 'BIGINT'})
                """.formatted(table, Bronze.shaOf("filename"), files));
    }

    /** Uma barra por (mercado, símbolo, minuto): a vista primeiro. */
    private static void dedup(LakeSql sql, String raw, String meta, String cutoff, String out) {
        sql.execute("""
                CREATE OR REPLACE TEMP TABLE %4$s AS
                SELECT r.market, r.symbol, r.broker_symbol,
                       CAST(replace(r.time_utc, 'Z', '') AS TIMESTAMP) AS time_utc,
                       r.open, r.high, r.low, r.close, r.tick_volume, r.spread_points, r.real_volume,
                       m.origin, m.seen_utc
                  FROM %1$s r JOIN %2$s m ON m.sha = r.sha
                 WHERE CAST(replace(r.time_utc, 'Z', '') AS TIMESTAMP) >= %3$s
                QUALIFY row_number() OVER (PARTITION BY r.market, r.symbol, r.time_utc
                                           ORDER BY m.seen_utc, m.origin) = 1
                """.formatted(raw, meta, cutoff, out));
    }

    public Report run(LakeSql sql, LocalDate utcReliableFrom) {
        Path out = lakeRoot.resolve("silver").resolve(TABLE);
        if (!Bronze.hasCsv(lakeRoot, SOURCE)) {
            return new Report(0, 0, 0, 0, utcReliableFrom, 0, List.of(), WeeklyOpenCheck.evaluate(List.of()), out);
        }
        String cutoff = utcReliableFrom == null ? "TIMESTAMP '1970-01-01'"
                : "TIMESTAMP '" + utcReliableFrom + " 00:00:00'";
        Bronze.createMetaTable(sql, lakeRoot, SOURCE, "candle_meta");
        readRaw(sql, Bronze.csvGlob(lakeRoot, SOURCE), "raw_candles");
        long files = sql.scalar("SELECT count(DISTINCT sha) FROM raw_candles");
        long rowsRead = sql.scalar("SELECT count(*) FROM raw_candles");
        long withoutMeta = sql.scalar("SELECT count(*) FROM raw_candles r ANTI JOIN candle_meta m ON m.sha = r.sha");

        dedup(sql, "raw_candles", "candle_meta", cutoff, "candles");
        long rowsWritten = sql.scalar("SELECT count(*) FROM candles");
        long beforeReliable = sql.scalar("SELECT count(DISTINCT (market, symbol, time_utc)) FROM raw_candles "
                + "WHERE CAST(replace(time_utc, 'Z', '') AS TIMESTAMP) < " + cutoff);

        Path tmp = out.resolveSibling(TABLE + ".tmp");
        LakeSql.deleteRecursively(tmp);
        sql.execute("""
                COPY (SELECT *, year(time_utc) AS year, month(time_utc) AS month
                        FROM candles ORDER BY market, symbol, time_utc)
                  TO %s (FORMAT PARQUET, COMPRESSION ZSTD, PARTITION_BY (market, symbol, year, month))
                """.formatted(LakeSql.literal(tmp)));
        LakeSql.replaceDirectory(tmp, out);

        sql.execute("""
                CREATE OR REPLACE TEMP TABLE candle_gaps AS
                SELECT market, symbol, time_utc, prev, date_diff('minute', prev, time_utc) AS gap
                  FROM (SELECT market, symbol, time_utc,
                               lag(time_utc) OVER (PARTITION BY market, symbol ORDER BY time_utc) AS prev
                          FROM candles)
                """);
        List<SymbolStats> stats = sql.query("""
                SELECT g.market, g.symbol, count(*) AS bars,
                       any_value(d.n_read) - count(*) AS dups,
                       CAST(min(g.time_utc) AS VARCHAR), CAST(max(g.time_utc) AS VARCHAR),
                       count(*) FILTER (WHERE g.gap > 10 AND g.gap < 360) AS gaps,
                       coalesce(max(g.gap) FILTER (WHERE g.gap < 360), 0) AS max_gap,
                       CAST(arg_max(g.prev, g.gap) FILTER (WHERE g.gap < 360) AS VARCHAR) AS max_gap_after
                  FROM candle_gaps g
                  JOIN (SELECT market, symbol, count(*) AS n_read FROM raw_candles
                         WHERE CAST(replace(time_utc, 'Z', '') AS TIMESTAMP) >= %s GROUP BY ALL) d
                    ON d.market = g.market AND d.symbol = g.symbol
                 GROUP BY g.market, g.symbol
                 ORDER BY g.market, g.symbol
                """.formatted(cutoff), rs -> new SymbolStats(rs.getString(1), rs.getString(2), rs.getLong(3), rs.getLong(4),
                Bronze.ts(rs.getString(5)), Bronze.ts(rs.getString(6)), rs.getLong(7), rs.getLong(8),
                Bronze.ts(rs.getString(9))));

        List<LocalDateTime> opens = sql.query(
                "SELECT CAST(time_utc AS VARCHAR) FROM candle_gaps WHERE gap >= 36 * 60",
                rs -> Bronze.ts(rs.getString(1)));
        return new Report(files, rowsRead, rowsWritten, withoutMeta, utcReliableFrom, beforeReliable, stats,
                WeeklyOpenCheck.evaluate(opens), out);
    }
}
