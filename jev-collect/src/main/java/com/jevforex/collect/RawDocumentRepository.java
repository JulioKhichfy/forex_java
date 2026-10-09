package com.jevforex.collect;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** Índice do bronze no PostgreSQL (tabela raw_document). */
@Repository
public class RawDocumentRepository {

    private static final String COLUMNS = """
            id, source, feed_id, issuer, currency, market, doc_type, url, title, sha256, lake_path,
            published_at, first_seen_at, parent_id, content_type""";

    private final ObjectProvider<JdbcClient> jdbcProvider;

    public RawDocumentRepository(ObjectProvider<JdbcClient> jdbcProvider) {
        this.jdbcProvider = jdbcProvider;
    }

    private JdbcClient jdbc() {
        JdbcClient c = jdbcProvider.getIfAvailable();
        if (c == null) {
            throw new IllegalStateException("Banco de dados indisponível neste comando. Suba o Postgres (docker compose up -d).");
        }
        return c;
    }

    public boolean exists(String source, String url) {
        return jdbc().sql("SELECT count(*) FROM raw_document WHERE source = :s AND url = :u")
                .param("s", source).param("u", url)
                .query(Long.class).single() > 0;
    }

    public long insert(RawDocument d) {
        return jdbc().sql("""
                        INSERT INTO raw_document
                          (source, feed_id, issuer, currency, market, doc_type, url, title, sha256, lake_path,
                           published_at, first_seen_at, parent_id, content_type)
                        VALUES (:source, :feed, :issuer, :currency, :market, :docType, :url, :title, :sha, :path,
                                :published, :firstSeen, :parent, :contentType)
                        ON CONFLICT (source, url) DO NOTHING
                        RETURNING id
                        """)
                .param("source", d.source())
                .param("feed", d.feedId())
                .param("issuer", d.issuer())
                .param("currency", d.currency())
                .param("market", d.market())
                .param("docType", d.docType())
                .param("url", d.url())
                .param("title", d.title())
                .param("sha", d.sha256())
                .param("path", d.lakePath())
                .param("published", d.publishedAt() == null ? null : Timestamp.from(d.publishedAt()),
                        Types.TIMESTAMP)
                .param("firstSeen", Timestamp.from(d.firstSeenAt()))
                .param("parent", d.parentId(), Types.BIGINT)
                .param("contentType", d.contentType())
                .query(Long.class).optional().orElse(-1L);
    }

    /** Documento mais recente de um tipo, para testes rápidos com o Jev. */
    public Optional<RawDocument> latest(String docType) {
        return jdbc().sql("SELECT " + COLUMNS + """
                         FROM raw_document
                         WHERE doc_type = :t
                         ORDER BY first_seen_at DESC, id DESC
                         LIMIT 1
                        """)
                .param("t", docType)
                .query((rs, i) -> map(rs))
                .optional();
    }

    public Optional<RawDocument> byId(long id) {
        return jdbc().sql("SELECT " + COLUMNS + " FROM raw_document WHERE id = :id")
                .param("id", id)
                .query((rs, i) -> map(rs))
                .optional();
    }

    /** Páginas HTML cujos anexos ainda não foram procurados (inclui as coletadas antes do passo 3b). */
    public List<RawDocument> pendingAttachmentCheck(int limit) {
        return jdbc().sql("SELECT " + COLUMNS + """
                         FROM raw_document
                         WHERE content_type = 'html' AND parent_id IS NULL AND attachments_checked_at IS NULL
                         ORDER BY id
                         LIMIT :n
                        """)
                .param("n", limit)
                .query((rs, i) -> map(rs))
                .list();
    }

    public void markAttachmentsChecked(long id) {
        jdbc().sql("UPDATE raw_document SET attachments_checked_at = now() WHERE id = :id").param("id", id).update();
    }

    private static RawDocument map(java.sql.ResultSet rs) throws java.sql.SQLException {
        Timestamp pub = rs.getTimestamp("published_at");
        long parent = rs.getLong("parent_id");
        Long parentId = rs.wasNull() ? null : parent;   // wasNull vale para a última coluna lida
        return new RawDocument(
                rs.getLong("id"), rs.getString("source"), rs.getString("feed_id"), rs.getString("issuer"),
                rs.getString("currency"), rs.getString("market"), rs.getString("doc_type"), rs.getString("url"),
                rs.getString("title"), rs.getString("sha256"), rs.getString("lake_path"),
                pub == null ? null : pub.toInstant(), rs.getTimestamp("first_seen_at").toInstant(),
                parentId, rs.getString("content_type"));
    }

    /**
     * @param parentId    página de onde veio este anexo (null = item do feed)
     * @param contentType html | pdf | txt
     */
    public record RawDocument(long id, String source, String feedId, String issuer, String currency, String market,
                              String docType, String url, String title, String sha256, String lakePath,
                              Instant publishedAt, Instant firstSeenAt, Long parentId, String contentType) {
    }
}
