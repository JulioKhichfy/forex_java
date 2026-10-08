package com.jevforex.app.persistence;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Types;

/** Auditoria de cada chamada ao Jev (tabela jev_call). */
@Repository
public class JevCallRepository {

    private final ObjectProvider<JdbcClient> jdbcProvider;

    public JevCallRepository(ObjectProvider<JdbcClient> jdbcProvider) {
        this.jdbcProvider = jdbcProvider;
    }

    public record JevCall(Long rawDocumentId, String market, String qsetCode, String qsetSha, String modelRequested,
                          String modelResolved, String requestSha, String requestJson, String responseJson,
                          Integer httpStatus, Integer inputTokens, Integer latencyMs, String error) {
    }

    public long insert(JevCall c) {
        JdbcClient jdbc = jdbcProvider.getIfAvailable();
        if (jdbc == null) return -1;    // comando sem banco: não grava
        return jdbc.sql("""
                        INSERT INTO jev_call (raw_document_id, market, qset_code, qset_sha, model_requested,
                                              model_resolved, request_sha, request_json, response_json,
                                              http_status, input_tokens, latency_ms, error)
                        VALUES (:doc, :market, :qset, :qsha, :mreq, :mres, :rsha, CAST(:req AS jsonb),
                                CAST(:resp AS jsonb), :status, :tokens, :latency, :error)
                        RETURNING id
                        """)
                .param("doc", c.rawDocumentId(), Types.BIGINT)
                .param("market", c.market())
                .param("qset", c.qsetCode())
                .param("qsha", c.qsetSha())
                .param("mreq", c.modelRequested())
                .param("mres", c.modelResolved(), Types.VARCHAR)
                .param("rsha", c.requestSha())
                .param("req", c.requestJson())
                .param("resp", c.responseJson(), Types.VARCHAR)
                .param("status", c.httpStatus(), Types.INTEGER)
                .param("tokens", c.inputTokens(), Types.INTEGER)
                .param("latency", c.latencyMs(), Types.INTEGER)
                .param("error", c.error(), Types.VARCHAR)
                .query(Long.class).single();
    }
}
