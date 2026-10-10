package com.jevforex.normalize;

import com.jevforex.lake.LakeSql;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * bronze/mt5_calendar → silver/calendar_events/market=fx/year=…/*.parquet
 * bronze/mt5_calendar_events → silver/calendar_event_defs/*.parquet
 *
 * <p>Point-in-time (documento mestre, capítulo 8): cada ESTADO distinto de um valor é uma linha
 * (forecast antes da divulgação, actual depois, revisões), com o primeiro momento em que foi visto.
 * {@code actual_available_utc} é quando o actual pode ser usado no backtest:</p>
 * <ul>
 *   <li>origin LIVE: o seen_utc real (o exportador viu ao vivo);</li>
 *   <li>SNAPSHOT/HISTORY: estimativa scheduled_utc + latência configurada
 *       ({@code availability_estimated = true}).</li>
 * </ul>
 * <p>Valores: inteiros ×10^6 do MT5 divididos; LONG_MIN (ausente) vira NULL.</p>
 */
public final class CalendarNormalizer {

    public static final String SOURCE = "mt5_calendar";
    public static final String DEFS_SOURCE = "mt5_calendar_events";
    public static final String TABLE = "calendar_events";
    public static final String DEFS_TABLE = "calendar_event_defs";

    private static final String MQL_LONG_MIN = "-9223372036854775808";

    private final Path lakeRoot;

    public CalendarNormalizer(Path lakeRoot) {
        this.lakeRoot = lakeRoot;
    }

    /** Latência real do coletor: do horário agendado até o exportador ver o actual ao vivo. */
    public record Latency(long samples, Double medianSeconds, Double p90Seconds, Double maxSeconds) {
    }

    public record Report(long files, long statesRead, long statesWritten, Map<String, Long> statesByOrigin,
                         long valuesWithActual, Latency liveLatency, long eventDefs, Path output) {
    }

    /**
     * Modo ao vivo: cria a temp table {@code table} (o mesmo formato do silver, sem market/year) só com os arquivos
     * do bronze gravados a partir de {@code since}. Sem arquivos: tabela vazia.
     *
     * @return quantos arquivos entraram
     */
    public static int createRecent(LakeSql sql, Path lakeRoot, java.time.Instant since, int actualLatencySeconds,
                                   String table) {
        java.util.List<Path> csv = Bronze.recentCsv(lakeRoot, SOURCE, since);
        java.util.List<Path> meta = Bronze.metaOf(csv);
        if (csv.isEmpty() || meta.isEmpty()) {
            sql.execute("CREATE OR REPLACE TEMP TABLE " + table + "_meta AS SELECT NULL::VARCHAR AS sha, "
                    + "NULL::VARCHAR AS origin, NULL::TIMESTAMP AS seen_utc WHERE false");
            sql.execute("CREATE OR REPLACE TEMP TABLE " + table + "_raw AS SELECT NULL::VARCHAR AS value_id, "
                    + "NULL::VARCHAR AS event_id, NULL::VARCHAR AS event_code, NULL::VARCHAR AS currency, "
                    + "NULL::VARCHAR AS country, NULL::VARCHAR AS importance, NULL::VARCHAR AS time_server, "
                    + "NULL::VARCHAR AS time_utc, NULL::VARCHAR AS period, NULL::VARCHAR AS revision, "
                    + "NULL::VARCHAR AS actual_raw, NULL::VARCHAR AS forecast_raw, NULL::VARCHAR AS prev_raw, "
                    + "NULL::VARCHAR AS revised_prev_raw, NULL::VARCHAR AS impact, NULL::VARCHAR AS sha WHERE false");
        } else {
            Bronze.createMetaTable(sql, Bronze.list(meta), table + "_meta");
            readRaw(sql, Bronze.list(csv), table + "_raw");
        }
        states(sql, table + "_raw", table + "_meta", actualLatencySeconds, table);
        return csv.size();
    }

    private static void readRaw(LakeSql sql, String files, String table) {
        sql.execute("""
                CREATE OR REPLACE TEMP TABLE %s AS
                SELECT *, %s AS sha
                  FROM read_csv(%s, skip = 1, header = true, delim = ';', filename = true, auto_detect = false,
                                columns = {'value_id': 'VARCHAR', 'event_id': 'VARCHAR', 'event_code': 'VARCHAR',
                                           'currency': 'VARCHAR', 'country': 'VARCHAR', 'importance': 'VARCHAR',
                                           'time_server': 'VARCHAR', 'time_utc': 'VARCHAR', 'period': 'VARCHAR',
                                           'revision': 'VARCHAR', 'actual_raw': 'VARCHAR',
                                           'forecast_raw': 'VARCHAR', 'prev_raw': 'VARCHAR',
                                           'revised_prev_raw': 'VARCHAR', 'impact': 'VARCHAR'})
                """.formatted(table, Bronze.shaOf("filename"), files));
    }

    /** 1) tipa e decodifica; 2) um registro por estado, o visto primeiro; 3) quando o actual pode ser usado. */
    private static void states(LakeSql sql, String raw, String meta, int actualLatencySeconds, String out) {
        sql.execute("""
                CREATE OR REPLACE TEMP TABLE %s_typed AS
                SELECT CAST(r.value_id AS UBIGINT) AS value_id,
                       CAST(r.event_id AS UBIGINT) AS event_id,
                       r.event_code, r.currency, nullif(r.country, '') AS country, r.importance,
                       CAST(replace(r.time_utc, 'Z', '') AS TIMESTAMP) AS scheduled_utc,
                       TRY_CAST(nullif(r.period, '') AS DATE) AS period,
                       TRY_CAST(nullif(r.revision, '') AS INTEGER) AS revision,
                       %s AS actual, %s AS forecast, %s AS previous, %s AS revised_previous,
                       nullif(r.impact, '') AS impact,
                       m.origin, m.seen_utc
                  FROM %s r JOIN %s m ON m.sha = r.sha
                """.formatted(out, decode("r.actual_raw"), decode("r.forecast_raw"), decode("r.prev_raw"),
                decode("r.revised_prev_raw"), raw, meta));
        sql.execute("""
                CREATE OR REPLACE TEMP TABLE %1$s AS
                SELECT *,
                       CASE WHEN actual IS NULL THEN NULL
                            WHEN origin = 'LIVE' THEN seen_utc
                            ELSE scheduled_utc + to_seconds(%2$d) END AS actual_available_utc,
                       origin <> 'LIVE' AS availability_estimated
                  FROM %1$s_typed
                QUALIFY row_number() OVER (PARTITION BY value_id, scheduled_utc, revision, actual, forecast,
                                                        previous, revised_previous, period, impact
                                           ORDER BY seen_utc, origin) = 1
                """.formatted(out, actualLatencySeconds));
    }

    public Report run(LakeSql sql, int actualLatencySeconds) {
        Path out = lakeRoot.resolve("silver").resolve(TABLE);
        if (!Bronze.hasCsv(lakeRoot, SOURCE)) {
            return new Report(0, 0, 0, Map.of(), 0, new Latency(0, null, null, null), 0, out);
        }
        Bronze.createMetaTable(sql, lakeRoot, SOURCE, "cal_meta");
        readRaw(sql, Bronze.csvGlob(lakeRoot, SOURCE), "raw_cal");
        long files = sql.scalar("SELECT count(DISTINCT sha) FROM raw_cal");
        long statesRead = sql.scalar("SELECT count(*) FROM raw_cal");

        states(sql, "raw_cal", "cal_meta", actualLatencySeconds, "cal_states");
        long statesWritten = sql.scalar("SELECT count(*) FROM cal_states");

        Path tmp = out.resolveSibling(TABLE + ".tmp");
        LakeSql.deleteRecursively(tmp);
        sql.execute("""
                COPY (SELECT *, 'fx' AS market, year(scheduled_utc) AS year
                        FROM cal_states ORDER BY scheduled_utc, value_id, seen_utc)
                  TO %s (FORMAT PARQUET, COMPRESSION ZSTD, PARTITION_BY (market, year))
                """.formatted(LakeSql.literal(tmp)));
        LakeSql.replaceDirectory(tmp, out);

        Map<String, Long> byOrigin = new LinkedHashMap<>();
        sql.query("SELECT origin, count(*) FROM cal_states GROUP BY origin ORDER BY origin",
                rs -> byOrigin.put(rs.getString(1), rs.getLong(2)));
        long withActual = sql.scalar("SELECT count(DISTINCT value_id) FROM cal_states WHERE actual IS NOT NULL");
        Latency latency = sql.query("""
                WITH v AS (
                    SELECT value_id, scheduled_utc,
                           min(seen_utc) FILTER (WHERE actual IS NOT NULL) AS actual_seen,
                           arg_min(origin, seen_utc) FILTER (WHERE actual IS NOT NULL) AS actual_origin,
                           min(seen_utc) FILTER (WHERE actual IS NULL) AS pending_seen
                      FROM cal_states GROUP BY value_id, scheduled_utc)
                SELECT count(*), median(s), quantile_cont(s, 0.9), max(s)
                  FROM (SELECT date_diff('millisecond', scheduled_utc, actual_seen) / 1000.0 AS s
                          FROM v
                         WHERE actual_origin = 'LIVE' AND pending_seen < actual_seen)
                """, rs -> new Latency(rs.getLong(1), (Double) rs.getObject(2), (Double) rs.getObject(3),
                (Double) rs.getObject(4))).get(0);

        long defs = Bronze.hasCsv(lakeRoot, DEFS_SOURCE) ? writeDefs(sql) : 0;
        return new Report(files, statesRead, statesWritten, byOrigin, withActual, latency, defs, out);
    }

    /** Dicionário de eventos: a versão mais recente de cada evento. */
    private long writeDefs(LakeSql sql) {
        Bronze.createMetaTable(sql, lakeRoot, DEFS_SOURCE, "defs_meta");
        sql.execute("""
                CREATE OR REPLACE TEMP TABLE defs AS
                SELECT CAST(r.event_id AS UBIGINT) AS event_id, r.event_code, r.name, r.currency,
                       nullif(r.country, '') AS country, r.importance, r.type, r.sector, r.frequency, r.time_mode,
                       r.unit, r.multiplier, TRY_CAST(r.digits AS INTEGER) AS digits,
                       nullif(r.source_url, '') AS source_url, m.seen_utc
                  FROM read_csv(%s, skip = 1, header = true, delim = ';', filename = true, auto_detect = false,
                                columns = {'event_id': 'VARCHAR', 'event_code': 'VARCHAR', 'name': 'VARCHAR',
                                           'currency': 'VARCHAR', 'country': 'VARCHAR', 'importance': 'VARCHAR',
                                           'type': 'VARCHAR', 'sector': 'VARCHAR', 'frequency': 'VARCHAR',
                                           'time_mode': 'VARCHAR', 'unit': 'VARCHAR', 'multiplier': 'VARCHAR',
                                           'digits': 'VARCHAR', 'source_url': 'VARCHAR'}) r
                  JOIN defs_meta m ON m.sha = %s
                QUALIFY row_number() OVER (PARTITION BY r.event_id ORDER BY m.seen_utc DESC) = 1
                """.formatted(Bronze.csvGlob(lakeRoot, DEFS_SOURCE), Bronze.shaOf("r.filename")));
        Path out = lakeRoot.resolve("silver").resolve(DEFS_TABLE);
        Path tmp = out.resolveSibling(DEFS_TABLE + ".tmp");
        LakeSql.deleteRecursively(tmp);
        tmp.toFile().mkdirs();
        sql.execute("COPY (SELECT * FROM defs ORDER BY event_id) TO "
                + LakeSql.literal(tmp.resolve("part-0.parquet")) + " (FORMAT PARQUET, COMPRESSION ZSTD)");
        LakeSql.replaceDirectory(tmp, out);
        return sql.scalar("SELECT count(*) FROM defs");
    }

    /** Inteiro ×10^6 do MT5 → DOUBLE; LONG_MIN ou vazio → NULL. */
    private static String decode(String col) {
        return "CASE WHEN %1$s IS NULL OR %1$s = '' OR %1$s = '%2$s' THEN NULL ELSE CAST(%1$s AS BIGINT) / 1e6 END"
                .formatted(col, MQL_LONG_MIN);
    }
}
