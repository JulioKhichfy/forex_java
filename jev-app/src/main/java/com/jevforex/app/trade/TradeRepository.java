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
                        Instant expiresAt, Instant sentAt, Instant doneAt, Double slPrice, Double tpPrice, String modifyMode) {

        public Order(Long id, String market, String symbol, String action, String side, Double volume,
                     Double slDistance, Double tpDistance, Double refPrice, Double atr, Double riskUsd,
                     Integer deviationPts, Instant closeAfterUtc, Long ticket, Long predictionId, String checksJson,
                     String status, String message, Double fillPrice, Long account, Instant createdAt,
                     Instant expiresAt, Instant sentAt, Instant doneAt, Double slPrice, Double tpPrice) {
            this(id, market, symbol, action, side, volume, slDistance, tpDistance, refPrice, atr, riskUsd, deviationPts,
                    closeAfterUtc, ticket, predictionId, checksJson, status, message, fillPrice, account, createdAt,
                    expiresAt, sentAt, doneAt, slPrice, tpPrice, null);
        }

        public Order(Long id, String market, String symbol, String action, String side, Double volume,
                     Double slDistance, Double tpDistance, Double refPrice, Double atr, Double riskUsd,
                     Integer deviationPts, Instant closeAfterUtc, Long ticket, Long predictionId, String checksJson,
                     String status, String message, Double fillPrice, Long account, Instant createdAt,
                     Instant expiresAt, Instant sentAt, Instant doneAt) {
            this(id, market, symbol, action, side, volume, slDistance, tpDistance, refPrice, atr, riskUsd, deviationPts,
                    closeAfterUtc, ticket, predictionId, checksJson, status, message, fillPrice, account, createdAt,
                    expiresAt, sentAt, doneAt, null, null, null);
        }
    }

    public long insertOrder(Order o) {
        return jdbc().sql("""
                        INSERT INTO order_request (market, symbol, action, side, volume, sl_distance, tp_distance, ref_price, atr, risk_usd,
                                                   deviation_pts, close_after_utc, ticket, prediction_id, checks, status,
                                                   message, account, expires_at, sl_price, tp_price, modify_mode)
                        VALUES (:market, :symbol, :action, :side, :volume, :sl, :tp, :ref, :atr, :risk, :dev, :closeAfter,
                                :ticket, :pred, CAST(:checks AS jsonb), :status, :message, :account, :expires, :slPrice,
                                :tpPrice, :mode)
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
                .param("slPrice", o.slPrice(), Types.DOUBLE)
                .param("tpPrice", o.tpPrice(), Types.DOUBLE)
                .param("mode", o.modifyMode(), Types.VARCHAR)
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

    /** Ordens abertas de um mercado (aceitas e ainda não concluídas pelo EA). */
    public long openOrders(String action, String market) {
        return jdbc().sql("SELECT count(*) FROM order_request WHERE action = :a AND market = :m AND status IN ('PENDING', 'SENT')")
                .param("a", action).param("m", market).query(Long.class).single();
    }

    /** Pedido de mudança de stop ainda em andamento para a posição (o trailing não empilha pedidos). */
    public boolean pendingModify(long ticket) {
        return jdbc().sql("SELECT count(*) FROM order_request WHERE action = 'MODIFY' AND ticket = :t "
                        + "AND status IN ('PENDING', 'SENT') AND expires_at > now()")
                .param("t", ticket).query(Long.class).single() > 0;
    }

    /** Mudança de stop recusada há pouco (mercado fechado: 15 min; outra recusa: 1 min) — o trailing espera. */
    public boolean modifyRecentlyRejected(long ticket) {
        return jdbc().sql("""
                        SELECT count(*) FROM order_request WHERE action = 'MODIFY' AND ticket = :t AND status = 'REJECTED'
                           AND done_at > now() - CASE WHEN message ILIKE '%market closed%' THEN INTERVAL '15 minutes'
                                                      ELSE INTERVAL '1 minute' END
                        """)
                .param("t", ticket).query(Long.class).single() > 0;
    }

    /**
     * Saída por tempo vencida de uma posição.
     *
     * @param lockUntil  fim da prorrogação com lucro (null = ainda não foi prorrogada)
     * @param lockStatus status do MODIFY da prorrogação (REJECTED/EXPIRED = não pegou: fechar)
     */
    public record DueExit(long ticket, Instant lockUntil, String lockStatus) {
    }

    /**
     * Fechamentos por tempo vencidos: posições abertas por ordens com close_after_utc no passado. Uma posição
     * prorrogada só volta quando a prorrogação acaba ou quando o EA recusou o novo stop/alvo.
     */
    public List<DueExit> dueTimeExits() {
        return jdbc().sql("""
                        SELECT o.ticket, o.time_lock_until, m.status AS lock_status
                          FROM order_request o JOIN ea_position p ON p.ticket = o.ticket
                          LEFT JOIN order_request m ON m.id = o.time_lock_order_id
                         WHERE p.account = (SELECT account FROM heartbeat WHERE component = 'ea'
                                             ORDER BY reported_at DESC NULLS LAST, id DESC LIMIT 1)
                           AND o.action = 'OPEN' AND o.status = 'FILLED' AND o.close_after_utc <= now()
                           AND (o.time_lock_until IS NULL OR o.time_lock_until <= now()
                                OR m.status IN ('REJECTED', 'EXPIRED', 'CANCELLED'))
                           AND NOT EXISTS (SELECT 1 FROM order_request c WHERE c.action = 'CLOSE' AND c.ticket = o.ticket
                                             AND (c.status IN ('PENDING', 'FILLED')
                                                  -- entregue e sem resposta do EA: depois de vencido, pede de novo
                                                  -- (15/10: um CLOSE SENT sem resposta deixou a posição aberta 2,5 h)
                                                  OR (c.status = 'SENT' AND c.expires_at > now() - INTERVAL '30 seconds')
                                                  -- recusado: espera antes de pedir de novo (16/10: "market closed" na
                                                  -- sexta virou um pedido a cada 5 s no fim de semana)
                                                  OR (c.status = 'REJECTED' AND c.done_at > now() - CASE
                                                        WHEN c.message ILIKE '%market closed%' THEN INTERVAL '15 minutes'
                                                        ELSE INTERVAL '1 minute' END)))
                        """)
                .query((rs, i) -> new DueExit(rs.getLong("ticket"), instant(rs, "time_lock_until"),
                        rs.getString("lock_status"))).list();
    }

    /** Início da prorrogação na linha OPEN da posição: a máxima, até quando e qual MODIFY levou o stop/alvo. */
    public void markTimeLock(long ticket, Instant started, Instant until, double peakUsd, long modifyOrderId) {
        jdbc().sql("""
                        UPDATE order_request SET time_lock_started_at = :s, time_lock_until = :u, time_lock_peak_usd = :p,
                               time_lock_peak_at = :s, time_lock_moves = 0, time_lock_order_id = :m
                         WHERE action = 'OPEN' AND ticket = :t
                        """)
                .param("s", ts(started), Types.TIMESTAMP).param("u", ts(until), Types.TIMESTAMP).param("p", peakUsd)
                .param("m", modifyOrderId).param("t", ticket).update();
    }

    /** Nova máxima de lucro na prorrogação: stop/alvo andaram e a janela renovou. */
    public void updateTimeLock(long ticket, Instant until, double peakUsd) {
        jdbc().sql("""
                        UPDATE order_request SET time_lock_until = :u, time_lock_peak_usd = :p, time_lock_peak_at = now(),
                               time_lock_moves = coalesce(time_lock_moves, 0) + 1
                         WHERE action = 'OPEN' AND ticket = :t
                        """)
                .param("u", ts(until), Types.TIMESTAMP).param("p", peakUsd).param("t", ticket).update();
    }

    /** Prorrogação em andamento de uma posição aberta (o MODIFY do início pegou ou ainda está a caminho). */
    public record ActiveLock(long ticket, Instant startedAt, Instant until, double peakUsd) {
    }

    public List<ActiveLock> activeTimeLocks() {
        return jdbc().sql("""
                        SELECT o.ticket, o.time_lock_started_at, o.time_lock_until, o.time_lock_peak_usd
                          FROM order_request o JOIN ea_position p ON p.ticket = o.ticket
                          LEFT JOIN order_request m ON m.id = o.time_lock_order_id
                         WHERE p.account = (SELECT account FROM heartbeat WHERE component = 'ea'
                                             ORDER BY reported_at DESC NULLS LAST, id DESC LIMIT 1)
                           AND o.action = 'OPEN' AND o.status = 'FILLED' AND o.time_lock_until > now()
                           AND o.time_lock_peak_usd IS NOT NULL
                           AND (m.status IS NULL OR m.status NOT IN ('REJECTED', 'EXPIRED', 'CANCELLED'))
                        """)
                .query((rs, i) -> new ActiveLock(rs.getLong("ticket"), instant(rs, "time_lock_started_at"),
                        instant(rs, "time_lock_until"), rs.getDouble("time_lock_peak_usd"))).list();
    }

    /**
     * De onde veio cada posição aberta e como ela é protegida: a ordem OPEN, a entrada da roleta (estratégia), a
     * previsão, a prorrogação e os movimentos do trailing. Uma linha por ticket (posição de fora do sistema não tem).
     */
    public List<java.util.Map<String, Object>> openOrderInfo(List<Long> tickets) {
        if (tickets.isEmpty()) return List.of();
        return jdbc().sql("""
                        SELECT o.ticket, o.id AS order_id, o.created_at, o.close_after_utc, o.time_lock_until,
                               o.time_lock_started_at, o.time_lock_peak_usd, o.time_lock_peak_at, o.time_lock_moves,
                               lk.status AS time_lock_status, lk.message AS time_lock_message,
                               o.sl_distance, o.tp_distance, o.atr, o.risk_usd, o.fill_price, CAST(o.checks AS text) AS checks,
                               e.strategy, e.p_side, e.pct_b,
                               coalesce(e.model, pr.model) AS model, coalesce(e.horizon_min, pr.horizon_min) AS horizon_min,
                               pr.p_down, pr.p_flat, pr.p_up,
                               (SELECT count(*) FROM order_request m WHERE m.action = 'MODIFY' AND m.ticket = o.ticket
                                   AND m.status = 'FILLED' AND m.tp_price IS NULL) AS trail_moves,
                               (SELECT m.message FROM order_request m WHERE m.action = 'MODIFY' AND m.ticket = o.ticket
                                   AND m.status = 'FILLED' AND m.tp_price IS NULL ORDER BY m.id DESC LIMIT 1) AS trail_last
                          FROM order_request o
                          LEFT JOIN roulette_entry e ON e.order_id = o.id
                          LEFT JOIN prediction pr ON pr.id = o.prediction_id
                          LEFT JOIN order_request lk ON lk.id = o.time_lock_order_id
                         WHERE o.action = 'OPEN' AND o.status = 'FILLED' AND o.ticket IN (:tickets)
                        """)
                .param("tickets", tickets).query().listOfRows();
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

    /**
     * Só as posições DESTE sistema (o magic do EA, que vem no heartbeat): as que ele pode fechar, editar, seguir com
     * trailing e fechar por tempo. As de fora (abertas à mão ou por outro robô, EA 2.8) entram só nas travas.
     */
    public List<Position> ownPositions() {
        Long magic = jdbc().sql("""
                        SELECT (detail::jsonb ->> 'magic')::bigint FROM heartbeat WHERE component = 'ea'
                         ORDER BY reported_at DESC NULLS LAST, id DESC LIMIT 1
                        """).query(Long.class).optional().orElse(null);
        return positions().stream().filter(p -> magic == null || p.magic() == null || p.magic().equals(magic)).toList();
    }

    /** Posições da conta conectada agora (último heartbeat): a foto de outra conta (ex.: a demo) fica de fora. */
    public List<Position> positions() {
        return jdbc().sql("""
                        SELECT * FROM ea_position
                         WHERE account = (SELECT account FROM heartbeat WHERE component = 'ea'
                                           ORDER BY reported_at DESC NULLS LAST, id DESC LIMIT 1)
                         ORDER BY open_time
                        """).query((rs, i) -> new Position(
                rs.getLong("ticket"), rs.getString("market"), rs.getLong("account"), rs.getString("symbol"),
                rs.getString("side"), rs.getDouble("volume"), rs.getDouble("open_price"), (Double) rs.getObject("sl"),
                (Double) rs.getObject("tp"), (Double) rs.getObject("profit"), (Long) rs.getObject("magic"),
                instant(rs, "open_time"), instant(rs, "reported_at"))).list();
    }

    // ------------------------------------------------------------------ fechamentos (placar das operações)

    public record Deal(long dealTicket, long positionTicket, long account, String market, String symbol, String side,
                       double volume, double price, double profit, double swap, double commission, String reason,
                       Instant closedAt) {
    }

    /** Grava os fechamentos reportados (repetidos são ignorados: o EA manda os últimos 7 dias a cada heartbeat). */
    public void upsertDeals(List<Deal> deals) {
        for (Deal d : deals) {
            jdbc().sql("""
                            INSERT INTO ea_deal (deal_ticket, position_ticket, account, market, symbol, side, volume, price,
                                                 profit, swap, commission, reason, closed_at)
                            VALUES (:deal, :pos, :account, :market, :symbol, :side, :volume, :price, :profit, :swap,
                                    :commission, :reason, :closed)
                            ON CONFLICT (deal_ticket) DO NOTHING
                            """)
                    .param("deal", d.dealTicket()).param("pos", d.positionTicket()).param("account", d.account())
                    .param("market", d.market()).param("symbol", d.symbol()).param("side", d.side())
                    .param("volume", d.volume()).param("price", d.price()).param("profit", d.profit())
                    .param("swap", d.swap()).param("commission", d.commission()).param("reason", d.reason())
                    .param("closed", ts(d.closedAt()), Types.TIMESTAMP)
                    .update();
        }
    }

    /**
     * Uma operação fechada: a ordem de entrada (lado, risco planejado) e o(s) fechamento(s) da posição.
     *
     * @param resultUsd lucro líquido (lucro + swap + comissão)
     * @param r         resultado ÷ risco planejado na entrada (perda máxima no stop); null sem a ordem de entrada
     */
    public record ClosedTrade(long positionTicket, String market, String symbol, String side, double volume,
                              Double entryPrice, Double exitPrice, Instant openedAt, Instant closedAt, String reason,
                              double resultUsd, Double riskUsd, Double r) {
    }

    public List<ClosedTrade> closedTrades(Instant from, int limit) {
        return jdbc().sql("""
                        WITH d AS (
                            SELECT position_ticket, min(market) AS market, min(symbol) AS symbol,
                                   sum(volume) AS volume, max(closed_at) AS closed_at,
                                   (array_agg(price ORDER BY closed_at DESC))[1] AS exit_price,
                                   (array_agg(reason ORDER BY closed_at DESC))[1] AS reason,
                                   sum(profit + swap + commission) AS result
                              FROM ea_deal
                             WHERE closed_at >= :from   -- só a conta conectada agora: a demo não se mistura com a real
                               AND account = (SELECT account FROM heartbeat WHERE component = 'ea'
                                               ORDER BY reported_at DESC NULLS LAST, id DESC LIMIT 1)
                             GROUP BY position_ticket)
                        SELECT d.*, o.side, o.fill_price, o.created_at AS opened_at, o.risk_usd
                          FROM d LEFT JOIN order_request o ON o.ticket = d.position_ticket AND o.action = 'OPEN'
                         ORDER BY d.closed_at DESC LIMIT :limit
                        """)
                .param("from", ts(from), Types.TIMESTAMP).param("limit", limit)
                .query((rs, i) -> {
                    Double risk = (Double) rs.getObject("risk_usd");
                    double result = rs.getDouble("result");
                    String side = rs.getString("side");
                    return new ClosedTrade(rs.getLong("position_ticket"), rs.getString("market"), rs.getString("symbol"),
                            side, rs.getDouble("volume"), (Double) rs.getObject("fill_price"),
                            (Double) rs.getObject("exit_price"), instant(rs, "opened_at"), instant(rs, "closed_at"),
                            rs.getString("reason"), result, risk, risk == null || risk <= 0 ? null : result / risk);
                }).list();
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
    /**
     * A conta do último heartbeat, com saldo e patrimônio JÁ EM US$ (conta cent: USC ÷ 100).
     *
     * @param currency a moeda da conta no MT5 (USD, USC…); o detalhe (margem) continua nela
     */
    public record Account(long account, String server, String tradeMode, Double equity, Double balance,
                          Instant reportedAt, String detailJson, String currency) {
    }

    public Optional<Account> latestAccount() {
        return jdbc().sql("""
                        SELECT account, server, trade_mode, equity, balance, currency, reported_at, CAST(detail AS text) AS detail
                          FROM heartbeat WHERE component = 'ea' ORDER BY reported_at DESC NULLS LAST, id DESC LIMIT 1
                        """)
                .query((rs, i) -> new Account(rs.getLong("account"), rs.getString("server"),
                        rs.getString("trade_mode"), AccountCurrency.toUsd((Double) rs.getObject("equity"), rs.getString("currency")),
                        AccountCurrency.toUsd((Double) rs.getObject("balance"), rs.getString("currency")),
                        instant(rs, "reported_at"), rs.getString("detail"), rs.getString("currency"))).optional();
    }

    /** Primeiro saldo reportado a partir de um instante (início do dia/semana UTC) — base da perda do período. */
    public Optional<Double> firstEquitySince(long account, Instant since) {
        return jdbc().sql("""
                        SELECT coalesce(balance, equity) * CASE WHEN upper(trim(currency)) = 'USC' THEN 0.01 ELSE 1 END
                          FROM heartbeat
                         WHERE component = 'ea' AND account = :a AND reported_at >= :since
                         ORDER BY reported_at LIMIT 1
                        """)   // em US$, como o latestAccount (conta cent: USC ÷ 100)
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
                instant(rs, "expires_at"), instant(rs, "sent_at"), instant(rs, "done_at"), (Double) rs.getObject("sl_price"),
                (Double) rs.getObject("tp_price"), rs.getString("modify_mode"));
    }

    private static Instant instant(ResultSet rs, String col) throws SQLException {
        Timestamp t = rs.getTimestamp(col);
        return t == null ? null : t.toInstant();
    }

    private static Timestamp ts(Instant i) {
        return i == null ? null : Timestamp.from(i);
    }
}
