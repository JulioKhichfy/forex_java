package com.jevforex.collect.mt5;

import com.jevforex.collect.mt5.Mt5Parsers.CalendarEventDef;
import com.jevforex.collect.mt5.Mt5Parsers.CalendarValue;
import com.jevforex.collect.mt5.Mt5Parsers.CandleSummary;
import com.jevforex.collect.mt5.Mt5Parsers.InstrumentSpec;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Date;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.function.Supplier;

/** Tabelas do passo 2: mt5_file, calendar_event(_def), instrument_spec, candle_status. */
@Repository
public class Mt5Repository {

    private final ObjectProvider<JdbcClient> jdbcProvider;
    private final ObjectProvider<NamedParameterJdbcTemplate> namedProvider;
    private final ObjectProvider<PlatformTransactionManager> txProvider;

    public Mt5Repository(ObjectProvider<JdbcClient> jdbcProvider,
                         ObjectProvider<NamedParameterJdbcTemplate> namedProvider,
                         ObjectProvider<PlatformTransactionManager> txProvider) {
        this.jdbcProvider = jdbcProvider;
        this.namedProvider = namedProvider;
        this.txProvider = txProvider;
    }

    private JdbcClient jdbc() {
        JdbcClient c = jdbcProvider.getIfAvailable();
        if (c == null) {
            throw new IllegalStateException("Banco de dados indisponível neste comando. Suba o Postgres (docker compose up -d).");
        }
        return c;
    }

    /** Executa tudo numa transação: o arquivo entra inteiro ou não entra. */
    public <T> T inTransaction(Supplier<T> work) {
        PlatformTransactionManager tm = txProvider.getIfAvailable();
        if (tm == null) throw new IllegalStateException("Banco de dados indisponível neste comando.");
        return new TransactionTemplate(tm).execute(status -> work.get());
    }

    // ------------------------------------------------------------------ mt5_file

    public boolean fileExists(String sha256) {
        return jdbc().sql("SELECT count(*) FROM mt5_file WHERE sha256 = :s")
                .param("s", sha256).query(Long.class).single() > 0;
    }

    public long insertFile(Mt5FileKind kind, String market, String fileName, String sha256, String lakePath, int rows,
                           Instant minTime, Instant maxTime, Instant firstSeenAt) {
        return jdbc().sql("""
                        INSERT INTO mt5_file (kind, market, file_name, sha256, lake_path, rows, min_time_utc,
                                              max_time_utc, first_seen_at)
                        VALUES (:kind, :market, :name, :sha, :path, :rows, :min, :max, :seen)
                        RETURNING id
                        """)
                .param("kind", kind.prefix())
                .param("market", market)
                .param("name", fileName)
                .param("sha", sha256)
                .param("path", lakePath)
                .param("rows", rows)
                .param("min", ts(minTime), Types.TIMESTAMP)
                .param("max", ts(maxTime), Types.TIMESTAMP)
                .param("seen", ts(firstSeenAt))
                .query(Long.class).single();
    }

    // ------------------------------------------------------------------ calendário

    /**
     * Insere os estados novos. Se o mesmo estado já existe, mantém o MENOR first_seen_at
     * (e a origem correspondente): vale o primeiro momento em que o dado foi visto.
     *
     * @return quantos estados foram gravados (inéditos ou vistos mais cedo do que o registrado)
     */
    public int insertCalendarValues(long fileId, List<CalendarValue> values) {
        if (values.isEmpty()) return 0;
        MapSqlParameterSource[] batch = values.stream().map(v -> new MapSqlParameterSource()
                .addValue("valueId", v.valueId())
                .addValue("eventId", v.eventId())
                .addValue("code", v.eventCode())
                .addValue("currency", v.currency())
                .addValue("country", v.country())
                .addValue("importance", v.importance())
                .addValue("sched", ts(v.scheduledAt()))
                .addValue("offset", v.offsetSeconds())
                .addValue("period", v.period() == null ? null : Date.valueOf(v.period()), Types.DATE)
                .addValue("revision", v.revision(), Types.INTEGER)
                .addValue("actual", v.actual(), Types.NUMERIC)
                .addValue("forecast", v.forecast(), Types.NUMERIC)
                .addValue("previous", v.previous(), Types.NUMERIC)
                .addValue("revPrev", v.revisedPrevious(), Types.NUMERIC)
                .addValue("impact", v.impact(), Types.VARCHAR)
                .addValue("origin", v.origin())
                .addValue("sha", v.stateSha())
                .addValue("seen", ts(v.firstSeenAt()))
                .addValue("file", fileId)).toArray(MapSqlParameterSource[]::new);
        int[] counts = namedProvider.getObject().batchUpdate("""
                INSERT INTO calendar_event (mt5_value_id, mt5_event_id, event_code, currency, country, importance,
                                            scheduled_at, server_offset_s, period, revision, actual, forecast,
                                            previous, revised_previous, impact, origin, state_sha, first_seen_at,
                                            mt5_file_id)
                VALUES (:valueId, :eventId, :code, :currency, :country, :importance, :sched, :offset, :period,
                        :revision, :actual, :forecast, :previous, :revPrev, :impact, :origin, :sha, :seen, :file)
                ON CONFLICT (mt5_value_id, state_sha) DO UPDATE
                   SET first_seen_at = EXCLUDED.first_seen_at,
                       origin        = EXCLUDED.origin,
                       mt5_file_id   = EXCLUDED.mt5_file_id
                 WHERE EXCLUDED.first_seen_at < calendar_event.first_seen_at
                """, batch);
        return Arrays.stream(counts).map(c -> Math.max(c, 0)).sum();
    }

    public int upsertCalendarEvents(List<CalendarEventDef> defs, Instant seenAt) {
        if (defs.isEmpty()) return 0;
        MapSqlParameterSource[] batch = defs.stream().map(d -> new MapSqlParameterSource()
                .addValue("id", d.eventId())
                .addValue("code", d.eventCode())
                .addValue("name", d.name())
                .addValue("currency", d.currency())
                .addValue("country", d.country())
                .addValue("importance", d.importance())
                .addValue("type", d.type())
                .addValue("sector", d.sector())
                .addValue("frequency", d.frequency())
                .addValue("timeMode", d.timeMode())
                .addValue("unit", d.unit())
                .addValue("multiplier", d.multiplier())
                .addValue("digits", d.digits(), Types.INTEGER)
                .addValue("url", d.sourceUrl())
                .addValue("seen", ts(seenAt))).toArray(MapSqlParameterSource[]::new);
        namedProvider.getObject().batchUpdate("""
                INSERT INTO calendar_event_def (mt5_event_id, event_code, name, currency, country, importance,
                                                event_type, sector, frequency, time_mode, unit, multiplier, digits,
                                                source_url, first_seen_at)
                VALUES (:id, :code, :name, :currency, :country, :importance, :type, :sector, :frequency, :timeMode,
                        :unit, :multiplier, :digits, :url, :seen)
                ON CONFLICT (mt5_event_id) DO UPDATE
                   SET event_code = EXCLUDED.event_code, name = EXCLUDED.name, importance = EXCLUDED.importance,
                       event_type = EXCLUDED.event_type, sector = EXCLUDED.sector, frequency = EXCLUDED.frequency,
                       time_mode = EXCLUDED.time_mode, unit = EXCLUDED.unit, multiplier = EXCLUDED.multiplier,
                       digits = EXCLUDED.digits, source_url = EXCLUDED.source_url, updated_at = now()
                """, batch);
        return defs.size();
    }

    // ------------------------------------------------------------------ candles

    public void upsertCandleStatus(List<CandleSummary> summaries) {
        for (CandleSummary s : summaries) {
            jdbc().sql("""
                            INSERT INTO candle_status (market, symbol, broker_symbol, first_bar_utc, last_bar_utc,
                                                       last_close, last_spread_points, bars_imported)
                            VALUES (:market, :symbol, :broker, :first, :last, :close, :spread, :bars)
                            ON CONFLICT (market, symbol) DO UPDATE SET
                                broker_symbol      = EXCLUDED.broker_symbol,
                                first_bar_utc      = LEAST(candle_status.first_bar_utc, EXCLUDED.first_bar_utc),
                                last_close         = CASE WHEN EXCLUDED.last_bar_utc >= candle_status.last_bar_utc
                                                          THEN EXCLUDED.last_close ELSE candle_status.last_close END,
                                last_spread_points = CASE WHEN EXCLUDED.last_bar_utc >= candle_status.last_bar_utc
                                                          THEN EXCLUDED.last_spread_points
                                                          ELSE candle_status.last_spread_points END,
                                last_bar_utc       = GREATEST(candle_status.last_bar_utc, EXCLUDED.last_bar_utc),
                                bars_imported      = candle_status.bars_imported + EXCLUDED.bars_imported,
                                updated_at         = now()
                            """)
                    .param("market", s.market())
                    .param("symbol", s.symbol())
                    .param("broker", s.brokerSymbol())
                    .param("first", ts(s.firstBar()))
                    .param("last", ts(s.lastBar()))
                    .param("close", s.lastClose())
                    .param("spread", s.lastSpreadPoints())
                    .param("bars", s.bars())
                    .update();
        }
    }

    // ------------------------------------------------------------------ especificações

    public void insertSpecs(long fileId, List<InstrumentSpec> specs) {
        for (InstrumentSpec s : specs) {
            jdbc().sql("""
                            INSERT INTO instrument_spec (market, symbol, broker_symbol, digits, point, tick_size,
                                tick_value, tick_value_profit, tick_value_loss, contract_size, volume_min,
                                volume_step, volume_max, currency_base, currency_profit, currency_margin,
                                account_currency, spread_points, stops_level, freeze_level, swap_long, swap_short,
                                trade_mode, bid, ask, server, seen_at, mt5_file_id)
                            VALUES (:market, :symbol, :broker, :digits, :point, :tickSize, :tickValue, :tvProfit,
                                :tvLoss, :contract, :vmin, :vstep, :vmax, :base, :profit, :margin, :account, :spread,
                                :stops, :freeze, :swapLong, :swapShort, :tradeMode, :bid, :ask, :server, :seen, :file)
                            """)
                    .param("market", s.market())
                    .param("symbol", s.symbol())
                    .param("broker", s.brokerSymbol())
                    .param("digits", s.digits())
                    .param("point", s.point())
                    .param("tickSize", s.tickSize())
                    .param("tickValue", s.tickValue())
                    .param("tvProfit", s.tickValueProfit(), Types.DOUBLE)
                    .param("tvLoss", s.tickValueLoss(), Types.DOUBLE)
                    .param("contract", s.contractSize())
                    .param("vmin", s.volumeMin())
                    .param("vstep", s.volumeStep())
                    .param("vmax", s.volumeMax())
                    .param("base", s.currencyBase(), Types.VARCHAR)
                    .param("profit", s.currencyProfit(), Types.VARCHAR)
                    .param("margin", s.currencyMargin(), Types.VARCHAR)
                    .param("account", s.accountCurrency())
                    .param("spread", s.spreadPoints(), Types.INTEGER)
                    .param("stops", s.stopsLevel(), Types.INTEGER)
                    .param("freeze", s.freezeLevel(), Types.INTEGER)
                    .param("swapLong", s.swapLong(), Types.DOUBLE)
                    .param("swapShort", s.swapShort(), Types.DOUBLE)
                    .param("tradeMode", s.tradeMode(), Types.VARCHAR)
                    .param("bid", s.bid(), Types.DOUBLE)
                    .param("ask", s.ask(), Types.DOUBLE)
                    .param("server", s.server(), Types.VARCHAR)
                    .param("seen", ts(s.seenAt()))
                    .param("file", fileId)
                    .update();
        }
    }

    /** A especificação mais recente de cada símbolo (usada pelo comando risk --mt5). */
    public List<InstrumentSpec> latestSpecs() {
        return jdbc().sql("""
                        SELECT DISTINCT ON (symbol) *
                          FROM instrument_spec
                         ORDER BY symbol, seen_at DESC, id DESC
                        """)
                .query((rs, i) -> new InstrumentSpec(rs.getString("market"), rs.getString("symbol"),
                        rs.getString("broker_symbol"), rs.getInt("digits"), rs.getDouble("point"),
                        rs.getDouble("tick_size"), rs.getDouble("tick_value"),
                        (Double) rs.getObject("tick_value_profit"), (Double) rs.getObject("tick_value_loss"),
                        rs.getDouble("contract_size"), rs.getDouble("volume_min"), rs.getDouble("volume_step"),
                        rs.getDouble("volume_max"), rs.getString("currency_base"), rs.getString("currency_profit"),
                        rs.getString("currency_margin"), rs.getString("account_currency"),
                        (Integer) rs.getObject("spread_points"), (Integer) rs.getObject("stops_level"),
                        (Integer) rs.getObject("freeze_level"), (Double) rs.getObject("swap_long"),
                        (Double) rs.getObject("swap_short"), rs.getString("trade_mode"),
                        (Double) rs.getObject("bid"), (Double) rs.getObject("ask"), rs.getString("server"),
                        rs.getTimestamp("seen_at").toInstant()))
                .list();
    }

    private static Timestamp ts(Instant i) {
        return i == null ? null : Timestamp.from(i);
    }
}
