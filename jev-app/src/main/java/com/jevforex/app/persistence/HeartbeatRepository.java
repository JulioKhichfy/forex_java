package com.jevforex.app.persistence;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;

/** Saúde do EA e dos coletores (tabela heartbeat). */
@Repository
public class HeartbeatRepository {

    private final ObjectProvider<JdbcClient> jdbcProvider;

    public HeartbeatRepository(ObjectProvider<JdbcClient> jdbcProvider) {
        this.jdbcProvider = jdbcProvider;
    }

    public record Heartbeat(String component, String market, Long account, String server, String tradeMode,
                            String eaMode, Double equity, Double balance, String currency, Integer openPositions,
                            Boolean algoEnabled, Boolean connected, Instant reportedAt, String detailJson) {
    }

    public void insert(Heartbeat h) {
        jdbcProvider.getObject().sql("""
                        INSERT INTO heartbeat (component, market, account, server, trade_mode, ea_mode, equity,
                                               balance, currency, open_positions, algo_enabled, connected,
                                               reported_at, detail)
                        VALUES (:component, :market, :account, :server, :tradeMode, :eaMode, :equity, :balance,
                                :currency, :positions, :algo, :connected, :reported, CAST(:detail AS jsonb))
                        """)
                .param("component", h.component())
                .param("market", h.market())
                .param("account", h.account(), Types.BIGINT)
                .param("server", h.server(), Types.VARCHAR)
                .param("tradeMode", h.tradeMode(), Types.VARCHAR)
                .param("eaMode", h.eaMode(), Types.VARCHAR)
                .param("equity", h.equity(), Types.DOUBLE)
                .param("balance", h.balance(), Types.DOUBLE)
                .param("currency", h.currency(), Types.VARCHAR)
                .param("positions", h.openPositions(), Types.INTEGER)
                .param("algo", h.algoEnabled(), Types.BOOLEAN)
                .param("connected", h.connected(), Types.BOOLEAN)
                .param("reported", h.reportedAt() == null ? null : Timestamp.from(h.reportedAt()), Types.TIMESTAMP)
                .param("detail", h.detailJson(), Types.VARCHAR)
                .update();
    }
}
