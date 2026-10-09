package com.jevforex.app.persistence;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Types;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Auditoria de cada chamada ao Jev (tabela jev_call). */
@Repository
public class JevCallRepository {

    private final ObjectProvider<JdbcClient> jdbcProvider;

    public JevCallRepository(ObjectProvider<JdbcClient> jdbcProvider) {
        this.jdbcProvider = jdbcProvider;
    }

    /**
     * @param source   cb_web | bis_speeches (null em perguntas avulsas com --text)
     * @param docSha   documento no silver
     * @param textSha  trecho avaliado
     * @param chunkIdx posição do trecho no documento
     */
    public record JevCall(Long rawDocumentId, String market, String qsetCode, String qsetSha, String modelRequested,
                          String modelResolved, String requestSha, String requestJson, String responseJson,
                          Integer httpStatus, Integer inputTokens, Integer latencyMs, String error,
                          String source, String docSha, String textSha, Integer chunkIdx) {

        /** Chamada avulsa (comando ask), sem trecho do silver. */
        public JevCall(Long rawDocumentId, String market, String qsetCode, String qsetSha, String modelRequested,
                       String modelResolved, String requestSha, String requestJson, String responseJson,
                       Integer httpStatus, Integer inputTokens, Integer latencyMs, String error) {
            this(rawDocumentId, market, qsetCode, qsetSha, modelRequested, modelResolved, requestSha, requestJson,
                    responseJson, httpStatus, inputTokens, latencyMs, error, null, null, null, null);
        }
    }

    public long insert(JevCall c) {
        JdbcClient jdbc = jdbcProvider.getIfAvailable();
        if (jdbc == null) return -1;    // comando sem banco: não grava
        return jdbc.sql("""
                        INSERT INTO jev_call (raw_document_id, market, qset_code, qset_sha, model_requested,
                                              model_resolved, request_sha, request_json, response_json,
                                              http_status, input_tokens, latency_ms, error,
                                              source, doc_sha, text_sha, chunk_idx)
                        VALUES (:doc, :market, :qset, :qsha, :mreq, :mres, :rsha, CAST(:req AS jsonb),
                                CAST(:resp AS jsonb), :status, :tokens, :latency, :error,
                                :source, :docSha, :textSha, :chunk)
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
                .param("source", c.source(), Types.VARCHAR)
                .param("docSha", c.docSha(), Types.CHAR)
                .param("textSha", c.textSha(), Types.CHAR)
                .param("chunk", c.chunkIdx(), Types.INTEGER)
                .query(Long.class).single();
    }

    /** request_sha já respondidos com sucesso (o cache: não paga de novo). */
    public Set<String> answeredRequests(String qsetCode, String model) {
        return new HashSet<>(jdbcProvider.getObject().sql("""
                        SELECT request_sha FROM jev_call
                         WHERE qset_code = :q AND model_requested = :m AND http_status = 200
                        """)
                .param("q", qsetCode).param("m", model)
                .query(String.class).list());
    }

    /**
     * Tokens de entrada por caractere do pedido, medido nas chamadas já feitas (para estimar custo antes de
     * chamar). NaN se ainda não houver chamadas.
     */
    public double tokensPerRequestChar() {
        Double v = jdbcProvider.getObject().sql("""
                        SELECT sum(input_tokens)::float8 / nullif(sum(length(request_json::text)), 0)
                          FROM jev_call WHERE http_status = 200 AND input_tokens > 0
                        """)
                .query(Double.class).optional().orElse(null);
        return v == null ? Double.NaN : v;
    }

    /** Respostas bem-sucedidas ligadas a trechos do silver. */
    public record Answer(String docSha, String textSha, Integer chunkIdx, String responseJson, String modelResolved,
                         Instant calledAt) {
    }

    public List<Answer> answers(String qsetCode, String model) {
        return jdbcProvider.getObject().sql("""
                        SELECT DISTINCT ON (text_sha) doc_sha, text_sha, chunk_idx, response_json::text, model_resolved,
                               called_at
                          FROM jev_call
                         WHERE qset_code = :q AND model_requested = :m AND http_status = 200 AND text_sha IS NOT NULL
                         ORDER BY text_sha, called_at DESC
                        """)
                .param("q", qsetCode).param("m", model)
                .query((rs, i) -> new Answer(rs.getString(1), rs.getString(2), (Integer) rs.getObject(3),
                        rs.getString(4), rs.getString(5), rs.getTimestamp(6).toInstant()))
                .list();
    }
}
