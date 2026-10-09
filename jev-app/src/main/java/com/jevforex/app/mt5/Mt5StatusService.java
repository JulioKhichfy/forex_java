package com.jevforex.app.mt5;

import com.jevforex.collect.mt5.Mt5Properties;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Saúde da integração com o MT5: heartbeat do EA, frescor dos candles, calendário, inbox.
 * Usado pelo /api/status e pelo comando mt5-status.
 */
@Service
public class Mt5StatusService {

    private final ObjectProvider<JdbcClient> jdbcProvider;
    private final Mt5Properties props;

    public Mt5StatusService(ObjectProvider<JdbcClient> jdbcProvider, Mt5Properties props) {
        this.jdbcProvider = jdbcProvider;
        this.props = props;
    }

    public Map<String, Object> status() {
        JdbcClient jdbc = jdbcProvider.getObject();
        Instant now = Instant.now();
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("common_files", props.commonFiles());
        s.put("inbox_pending", countFiles(props.inbox(), ".csv"));
        s.put("error_files", countFiles(props.errorDir(), ".csv"));
        s.put("expected_account_configured", props.expectedAccount() > 0);
        s.put("expected_server", props.expectedServer());

        s.put("ea_heartbeat", jdbc.sql("""
                        SELECT received_at, account, server, trade_mode, ea_mode, equity, balance, currency,
                               open_positions, algo_enabled, connected
                          FROM heartbeat WHERE component = 'ea'
                         ORDER BY received_at DESC LIMIT 1
                        """)
                .query((rs, i) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    Instant at = instant(rs, "received_at");
                    long age = Duration.between(at, now).toSeconds();
                    m.put("received_at", at.toString());
                    m.put("age_seconds", age);
                    m.put("stale", age > props.heartbeatStaleSeconds());
                    m.put("account", rs.getLong("account"));
                    m.put("server", rs.getString("server"));
                    m.put("trade_mode", rs.getString("trade_mode"));
                    m.put("ea_mode", rs.getString("ea_mode"));
                    m.put("equity", rs.getObject("equity"));
                    m.put("balance", rs.getObject("balance"));
                    m.put("currency", rs.getString("currency"));
                    m.put("open_positions", rs.getObject("open_positions"));
                    m.put("algo_enabled", rs.getObject("algo_enabled"));
                    m.put("connected", rs.getObject("connected"));
                    return m;
                }).optional().orElse(null));

        s.put("candles", jdbc.sql("""
                        SELECT market, symbol, broker_symbol, first_bar_utc, last_bar_utc, last_close,
                               last_spread_points, bars_imported
                          FROM candle_status ORDER BY market, symbol
                        """)
                .query((rs, i) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    Instant last = instant(rs, "last_bar_utc");
                    // a barra M1 de 12:31 fecha às 12:32: o atraso conta a partir do fechamento
                    long lag = Duration.between(last.plusSeconds(60), now).toSeconds();
                    m.put("market", rs.getString("market"));
                    m.put("symbol", rs.getString("symbol"));
                    m.put("broker_symbol", rs.getString("broker_symbol"));
                    m.put("first_bar_utc", instant(rs, "first_bar_utc").toString());
                    m.put("last_bar_utc", last.toString());
                    m.put("lag_seconds", lag);
                    m.put("stale", lag > props.candleStaleSeconds());
                    m.put("last_close", rs.getObject("last_close"));
                    m.put("last_spread_points", rs.getObject("last_spread_points"));
                    m.put("bars_imported", rs.getLong("bars_imported"));
                    return m;
                }).list());

        Map<String, Object> cal = new LinkedHashMap<>();
        cal.put("states_total", jdbc.sql("SELECT count(*) FROM calendar_event").query(Long.class).single());
        cal.put("states_live", jdbc.sql("SELECT count(*) FROM calendar_event WHERE origin = 'LIVE'")
                .query(Long.class).single());
        cal.put("event_defs", jdbc.sql("SELECT count(*) FROM calendar_event_def").query(Long.class).single());
        cal.put("last_seen_at", jdbc.sql("SELECT max(first_seen_at) FROM calendar_event")
                .query((rs, i) -> rs.getTimestamp(1) == null ? null : rs.getTimestamp(1).toInstant().toString())
                .single());
        s.put("calendar", cal);

        s.put("next_high_impact", jdbc.sql("""
                        SELECT c.scheduled_at, c.currency, c.event_code, COALESCE(d.name, c.event_code) AS event,
                               c.forecast, c.previous
                          FROM (SELECT DISTINCT ON (mt5_value_id) *
                                  FROM calendar_event
                                 WHERE scheduled_at > now() AND importance = 'HIGH'
                                 ORDER BY mt5_value_id, first_seen_at DESC, id DESC) c
                          LEFT JOIN calendar_event_def d ON d.mt5_event_id = c.mt5_event_id
                         ORDER BY c.scheduled_at LIMIT 10
                        """)
                .query((rs, i) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("scheduled_at", instant(rs, "scheduled_at").toString());
                    m.put("currency", rs.getString("currency"));
                    m.put("event_code", rs.getString("event_code"));
                    m.put("event", rs.getString("event"));
                    m.put("forecast", rs.getBigDecimal("forecast"));
                    m.put("previous", rs.getBigDecimal("previous"));
                    return m;
                }).list());

        s.put("files", jdbc.sql("""
                        SELECT kind, count(*) AS n, max(first_seen_at) AS last_seen
                          FROM mt5_file GROUP BY kind ORDER BY kind
                        """)
                .query((rs, i) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("kind", rs.getString("kind"));
                    m.put("count", rs.getLong("n"));
                    m.put("last_seen_at", instant(rs, "last_seen").toString());
                    return m;
                }).list());
        return s;
    }

    private static Instant instant(ResultSet rs, String col) throws SQLException {
        Timestamp t = rs.getTimestamp(col);
        return t == null ? null : t.toInstant();
    }

    private static long countFiles(Path dir, String suffix) {
        if (!Files.isDirectory(dir)) return 0;
        try (Stream<Path> s = Files.list(dir)) {
            return s.filter(p -> p.getFileName().toString().endsWith(suffix)).count();
        } catch (IOException e) {
            return -1;
        }
    }

    /** Lista de mapas com tipo genérico, para quem só imprime. */
    @SuppressWarnings("unchecked")
    public static List<Map<String, Object>> list(Map<String, Object> status, String key) {
        Object v = status.get(key);
        return v == null ? List.of() : (List<Map<String, Object>>) v;
    }
}
