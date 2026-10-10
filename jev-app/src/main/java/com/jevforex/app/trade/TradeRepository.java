package com.jevforex.app.trade;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** Ordens do dashboard, posições reportadas pelo EA e ajustes do dashboard (passo 6c). */
@Repository
public class TradeRepository {

    private final ObjectProvider<JdbcClient> jdbcProvider;

    public TradeRepository(ObjectProvider<JdbcClient> jdbcProvider) {
        this.jdbcProvider = jdbcProvider;
    }

    private JdbcClient jdbc() {
        return jdbcProvider.getObject();
    }

    // ------------------------------------------------------------------ ordens

    public record Order(Long id, String market, String symbol, String action, String side, Double volume,
                        Double slDistance, Double tpDistance, Double refPrice, Double atr, Double riskUsd,
                        Integer deviationPts, Instant closeAfterUtc, Long ticket, Long predictionId, String checksJson,
                        String status, String message, Double fillPrice, Long account, Instant createdAt,
                        Instant expiresAt, Instant sentAt, Instant doneAt) {
    }

    public long insertOrder(Order o) {
        return jdbc().sql("""
                        INSERT INTO order_request (market, symbol, action, side, volume, sl_distance, tp_distance, ref_price, atr, risk_usd,
                                                   deviation_pts, close_after_utc, ticket, prediction_id, checks, status,
                                                   message, account, expires_at)
                        VALUES (:market, :symbol, :action, :side, :volume, :sl, :tp, :ref, :atr, :risk, :dev, :closeAfter,
                                :ticket, :pred, CAST(:checks AS jsonb), :status, :message, :account, :expires)
                        RETURNING id
                        """)
                .param("market", o.market())
                .param("symbol", o.symbol())
                .param("action", o.action())
                .param("side", o.side(), Types.VARCHAR)
                .param("volume", o.volume(), Types.DOUBLE)
                .param("sl", o.slDistance(), Types.DOUBLE)
                .param("tp", o.tpDistance(), Types.DOUBLE)
                .param("ref", o.refPrice(), Types.DOUBLE)
                .param("atr", o.atr(), Types.DOUBLE)
                .param("risk", o.riskUsd(), Types.DOUBLE)
                .param("dev", o.deviationPts(), Types.INTEGER)
                .param("closeAfter", ts(o.closeAfterUtc()), Types.TIMESTAMP)
                .param("ticket", o.ticket(), Types.BIGINT)
                .param("pred", o.predictionId(), Types.BIGINT)
                .param("checks", o.checksJson(), Types.VARCHAR)
                .param("status", o.status())
                .param("message", o.message(), Types.VARCHAR)
                .param("account", o.account(), Types.BIGINT)
                .param("expires", ts(o.expiresAt()), Types.TIMESTAMP)
                .query(Long.class).single();
    }

    /**
     * Pedidos para o EA: os PENDING ainda válidos passam a SENT na mesma transação (cada um sai uma vez só);
     * os vencidos viram EXPIRED.
     */
    @Transactional
    public List<Order> takePending(long account) {
        jdbc().sql("UPDATE order_request SET status = 'EXPIRED', done_at = now(), message = 'venceu antes de o EA buscar' "
                + "WHERE status = 'PENDING' AND expires_at <= now()").update();
        List<Order> out = jdbc().sql("""
                        SELECT * FROM order_request
                         WHERE status = 'PENDING' AND expires_at > now() AND (account IS NULL OR account = :account)
                         ORDER BY id FOR UPDATE SKIP LOCKED
                        """)
                .param("account", account)
                .query(TradeRepository::order).list();
        for (Order o : out) {
            jdbc().sql("UPDATE order_request SET status = 'SENT', sent_at = now(), account = :account WHERE id = :id")
                    .param("account", account).param("id", o.id()).update();
        }
        return out;
    }

    /** Resultado que o EA reportou. */
    public void finish(long id, String status, Long ticket, Double fillPrice, String message) {
        jdbc().sql("""
                        UPDATE order_request SET status = :status, ticket = coalesce(:ticket, ticket),
                               fill_price = :price, message = :message, done_at = now()
                         WHERE id = :id AND status IN ('SENT', 'PENDING')
                        """)
                .param("id", id)
                .param("status", status)
                .param("ticket", ticket, Types.BIGINT)
                .param("price", fillPrice, Types.DOUBLE)
                .param("message", message, Types.VARCHAR)
                .update();
    }

    public void cancelPending(String reason) {
        jdbc().sql("UPDATE order_request SET status = 'CANCELLED', done_at = now(), message = :m "
                + "WHERE status = 'PENDING'").param("m", reason).update();
    }

    public List<Order> recentOrders(int limit) {
        return jdbc().sql("SELECT * FROM order_request ORDER BY id DESC LIMIT :limit")
                .param("limit", limit).query(TradeRepository::order).list();
    }

    /** Ordens abertas (aceitas pelo sistema e ainda não concluídas pelo EA). */
    public long openOrders(String action) {
        return jdbc().sql("SELECT count(*) FROM order_request WHERE action = :a AND status IN ('PENDING', 'SENT')")
                .param("a", action).query(Long.class).single();
    }

    /** Fechamentos por tempo vencidos: posições abertas por ordens com close_after_utc no passado. */
    public List<Long> dueTimeExits() {
        return jdbc().sql("""
                        SELECT o.ticket FROM order_request o JOIN ea_position p ON p.ticket = o.ticket
                         WHERE o.action = 'OPEN' AND o.status = 'FILLED' AND o.close_after_utc <= now()
                           AND NOT EXISTS (SELECT 1 FROM order_request c WHERE c.action = 'CLOSE' AND c.ticket = o.ticket
                                             AND c.status IN ('PENDING', 'SENT', 'FILLED'))
                        """)
                .query(Long.class).list();
    }

    // ------------------------------------------------------------------ posições

    public record Position(long ticket, String market, long account, String symbol, String side, double volume,
                           double openPrice, Double sl, Double tp, Double profit, Long magic, Instant openTime,
                           Instant reportedAt) {
    }

    /** Substitui a foto das posições da conta pelo que o EA acabou de reportar. */
    @Transactional
    public void replacePositions(long account, List<Position> positions) {
        jdbc().sql("DELETE FROM ea_position WHERE account = :a").param("a", account).update();
        for (Position p : positions) {
            jdbc().sql("""
                            INSERT INTO ea_position (ticket, market, account, symbol, side, volume, open_price, sl, tp,
                                                     profit, magic, open_time, reported_at)
                            VALUES (:ticket, :market, :account, :symbol, :side, :volume, :open, :sl, :tp, :profit,
                                    :magic, :openTime, :reported)
                            """)
                    .param("ticket", p.ticket())
                    .param("market", p.market())
                    .param("account", p.account())
                    .param("symbol", p.symbol())
                    .param("side", p.side())
                    .param("volume", p.volume())
                    .param("open", p.openPrice())
                    .param("sl", p.sl(), Types.DOUBLE)
                    .param("tp", p.tp(), Types.DOUBLE)
                    .param("profit", p.profit(), Types.DOUBLE)
                    .param("magic", p.magic(), Types.BIGINT)
                    .param("openTime", ts(p.openTime()), Types.TIMESTAMP)
                    .param("reported", ts(p.reportedAt()), Types.TIMESTAMP)
                    .update();
        }
    }

    public List<Position> positions() {
        return jdbc().sql("SELECT * FROM ea_position ORDER BY open_time").query((rs, i) -> new Position(
                rs.getLong("ticket"), rs.getString("market"), rs.getLong("account"), rs.getString("symbol"),
                rs.getString("side"), rs.getDouble("volume"), rs.getDouble("open_price"), (Double) rs.getObject("sl"),
                (Double) rs.getObject("tp"), (Double) rs.getObject("profit"), (Long) rs.getObject("magic"),
                instant(rs, "open_time"), instant(rs, "reported_at"))).list();
    }

    // ------------------------------------------------------------------ ajustes do dashboard

    public Optional<String> setting(String key) {
        return jdbc().sql("SELECT value FROM app_setting WHERE key = :k").param("k", key).query(String.class).optional();
    }

    public void setSetting(String key, String value) {
        jdbc().sql("""
                        INSERT INTO app_setting (key, value) VALUES (:k, :v)
                        ON CONFLICT (key) DO UPDATE SET value = EXCLUDED.value, updated_at = now()
                        """)
                .param("k", key).param("v", value).update();
    }

    // ------------------------------------------------------------------ conta (heartbeat do EA)

    /** Último heartbeat do EA e o detalhe em JSON (saldo, margem, cotações). */
    public record Account(long account, String server, String tradeMode, Double equity, Double balance,
                          Instant reportedAt, String detailJson) {
    }

    public Optional<Account> latestAccount() {
        return jdbc().sql("""
                        SELECT account, server, trade_mode, equity, balance, reported_at, CAST(detail AS text) AS detail
                          FROM heartbeat WHERE component = 'ea' ORDER BY reported_at DESC NULLS LAST, id DESC LIMIT 1
                        """)
                .query((rs, i) -> new Account(rs.getLong("account"), rs.getString("server"),
                        rs.getString("trade_mode"), (Double) rs.getObject("equity"), (Double) rs.getObject("balance"),
                        instant(rs, "reported_at"), rs.getString("detail"))).optional();
    }

    /** Primeiro saldo reportado a partir de um instante (início do dia/semana UTC) — base da perda do período. */
    public Optional<Double> firstEquitySince(long account, Instant since) {
        return jdbc().sql("""
                        SELECT coalesce(balance, equity) FROM heartbeat
                         WHERE component = 'ea' AND account = :a AND reported_at >= :since
                         ORDER BY reported_at LIMIT 1
                        """)
                .param("a", account).param("since", ts(since), Types.TIMESTAMP)
                .query(Double.class).optional();
    }

    private static Order order(ResultSet rs, int row) throws SQLException {
        return new Order(rs.getLong("id"), rs.getString("market"), rs.getString("symbol"), rs.getString("action"),
                rs.getString("side"), (Double) rs.getObject("volume"), (Double) rs.getObject("sl_distance"),
                (Double) rs.getObject("tp_distance"), (Double) rs.getObject("ref_price"), (Double) rs.getObject("atr"),
                (Double) rs.getObject("risk_usd"), (Integer) rs.getObject("deviation_pts"),
                instant(rs, "close_after_utc"), (Long) rs.getObject("ticket"), (Long) rs.getObject("prediction_id"),
                rs.getString("checks"), rs.getString("status"), rs.getString("message"),
                (Double) rs.getObject("fill_price"), (Long) rs.getObject("account"), instant(rs, "created_at"),
                instant(rs, "expires_at"), instant(rs, "sent_at"), instant(rs, "done_at"));
    }

    private static Instant instant(ResultSet rs, String col) throws SQLException {
        Timestamp t = rs.getTimestamp(col);
        return t == null ? null : t.toInstant();
    }

    private static Timestamp ts(Instant i) {
        return i == null ? null : Timestamp.from(i);
    }
}
