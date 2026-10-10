package com.jevforex.app.live;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.util.List;

/** Previsões ao vivo (tabela prediction). */
@Repository
public class PredictionRepository {

    private final ObjectProvider<JdbcClient> jdbcProvider;

    public PredictionRepository(ObjectProvider<JdbcClient> jdbcProvider) {
        this.jdbcProvider = jdbcProvider;
    }

    public record Prediction(Long id, String market, String symbol, Instant momentUtc, String kind, String trigger,
                             String model, int horizonMin, String modelVersion, double pDown, double pFlat,
                             double pUp, String signal, Double atr, Double close, Integer spreadPoints,
                             Integer dataLagS, String featuresJson, Instant createdAt, Instant resolvedAt,
                             String label, Double rBuy, Double rSell) {
    }

    /** @return false se a mesma previsão já existia (mesmo momento, modelo, horizonte, versão e gatilho) */
    public boolean insert(Prediction p) {
        return jdbcProvider.getObject().sql("""
                        INSERT INTO prediction (market, symbol, moment_utc, kind, trigger, model, horizon_min,
                                                model_version, p_down, p_flat, p_up, signal, atr, close,
                                                spread_points, data_lag_s, features)
                        VALUES (:market, :symbol, :moment, :kind, :trigger, :model, :h, :version, :pd, :pf, :pu,
                                :signal, :atr, :close, :spread, :lag, CAST(:features AS jsonb))
                        ON CONFLICT ON CONSTRAINT uq_prediction DO NOTHING
                        """)
                .param("market", p.market())
                .param("symbol", p.symbol())
                .param("moment", Timestamp.from(p.momentUtc()), Types.TIMESTAMP)
                .param("kind", p.kind())
                .param("trigger", p.trigger())
                .param("model", p.model())
                .param("h", p.horizonMin())
                .param("version", p.modelVersion())
                .param("pd", p.pDown())
                .param("pf", p.pFlat())
                .param("pu", p.pUp())
                .param("signal", p.signal())
                .param("atr", p.atr(), Types.DOUBLE)
                .param("close", p.close(), Types.DOUBLE)
                .param("spread", p.spreadPoints(), Types.INTEGER)
                .param("lag", p.dataLagS(), Types.INTEGER)
                .param("features", p.featuresJson(), Types.VARCHAR)
                .update() > 0;
    }

    /** A previsão mais recente de cada (par, modelo, horizonte). */
    public List<Prediction> latest(String market) {
        return jdbcProvider.getObject().sql("""
                        SELECT DISTINCT ON (symbol, model, horizon_min) *
                          FROM prediction
                         WHERE market = :market
                         ORDER BY symbol, model, horizon_min, moment_utc DESC, created_at DESC
                        """)
                .param("market", market)
                .query(PredictionRepository::map).list();
    }

    public List<Prediction> history(String market, String symbol, Instant from, int limit) {
        return jdbcProvider.getObject().sql("""
                        SELECT * FROM prediction
                         WHERE market = :market AND (:symbol IS NULL OR symbol = :symbol) AND moment_utc >= :from
                         ORDER BY moment_utc DESC, symbol, model, horizon_min
                         LIMIT :limit
                        """)
                .param("market", market)
                .param("symbol", symbol, Types.VARCHAR)
                .param("from", Timestamp.from(from), Types.TIMESTAMP)
                .param("limit", limit)
                .query(PredictionRepository::map).list();
    }

    /** Previsões com o horizonte vencido e ainda sem placar (até {@code limit}). */
    public List<Prediction> unresolved(Instant before, int limit) {
        return jdbcProvider.getObject().sql("""
                        SELECT * FROM prediction
                         WHERE resolved_at IS NULL AND moment_utc + make_interval(mins => horizon_min + 10) < :before
                         ORDER BY moment_utc LIMIT :limit
                        """)
                .param("before", Timestamp.from(before), Types.TIMESTAMP)
                .param("limit", limit)
                .query(PredictionRepository::map).list();
    }

    public void resolve(long id, String label, Double rBuy, Double rSell) {
        jdbcProvider.getObject().sql("""
                        UPDATE prediction SET resolved_at = now(), label = :label, r_buy = :rb, r_sell = :rs
                         WHERE id = :id
                        """)
                .param("id", id)
                .param("label", label, Types.VARCHAR)
                .param("rb", rBuy, Types.DOUBLE)
                .param("rs", rSell, Types.DOUBLE)
                .update();
    }

    /**
     * Placar ao vivo de um modelo: operações em que o gate 4 deu sinal e o resultado já é conhecido.
     *
     * @param n           sinais resolvidos
     * @param hits        sinais com resultado positivo
     * @param sumR        soma de R (resultado da operação no lado do sinal)
     * @param resolved    previsões resolvidas (com ou sem sinal)
     * @param directional previsões resolvidas em que a classe mais provável acertou o label
     */
    public record Score(String model, int horizonMin, long n, long hits, double sumR, long resolved,
                        long directional) {
    }

    public List<Score> scoreboard(String market, Instant from) {
        return jdbcProvider.getObject().sql("""
                        SELECT model, horizon_min,
                               count(*) FILTER (WHERE signal <> 'NO_TRADE' AND rr IS NOT NULL) AS n,
                               count(*) FILTER (WHERE signal <> 'NO_TRADE' AND rr > 0) AS hits,
                               coalesce(sum(rr) FILTER (WHERE signal <> 'NO_TRADE'), 0) AS sum_r,
                               count(*) AS resolved,
                               count(*) FILTER (WHERE label = top) AS directional
                          FROM (SELECT *, CASE signal WHEN 'BUY' THEN r_buy WHEN 'SELL' THEN r_sell END AS rr,
                                       CASE WHEN p_up >= p_down AND p_up >= p_flat THEN 'ALTA'
                                            WHEN p_down >= p_flat THEN 'QUEDA' ELSE 'LATERAL' END AS top
                                  FROM prediction
                                 WHERE market = :market AND resolved_at IS NOT NULL AND moment_utc >= :from
                                   AND trigger <> 'MANUAL') x
                         GROUP BY model, horizon_min ORDER BY model, horizon_min
                        """)
                .param("market", market)
                .param("from", Timestamp.from(from), Types.TIMESTAMP)
                .query((rs, i) -> new Score(rs.getString(1), rs.getInt(2), rs.getLong(3), rs.getLong(4),
                        rs.getDouble(5), rs.getLong(6), rs.getLong(7))).list();
    }

    private static Prediction map(ResultSet rs, int row) throws SQLException {
        return new Prediction(rs.getLong("id"), rs.getString("market"), rs.getString("symbol"),
                rs.getTimestamp("moment_utc").toInstant(), rs.getString("kind"), rs.getString("trigger"),
                rs.getString("model"), rs.getInt("horizon_min"), rs.getString("model_version"),
                rs.getDouble("p_down"), rs.getDouble("p_flat"), rs.getDouble("p_up"), rs.getString("signal"),
                (Double) rs.getObject("atr"), (Double) rs.getObject("close"), (Integer) rs.getObject("spread_points"),
                (Integer) rs.getObject("data_lag_s"), rs.getString("features"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("resolved_at") == null ? null : rs.getTimestamp("resolved_at").toInstant(),
                rs.getString("label"), (Double) rs.getObject("r_buy"), (Double) rs.getObject("r_sell"));
    }
}
