package com.jevforex.normalize;

import com.jevforex.lake.LakeSql;
import com.jevforex.lake.LocalDiskLakeStorage;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * bronze/cb_web → silver/documents/market=…/year=…/*.parquet: uma linha por TRECHO de texto.
 *
 * <p>O texto sai do HTML (corpo do artigo) ou do PDF anexo e é dividido em trechos de até ~6.000
 * caracteres respeitando parágrafos: é o que o Jev vai avaliar no passo 4. Cada trecho carrega o
 * first_seen_at do documento (point-in-time) e, se for de um anexo, o documento de origem.</p>
 */
public final class DocumentNormalizer {

    public static final String SOURCE = "cb_web";
    public static final String TABLE = "documents";

    private final Path lakeRoot;

    public DocumentNormalizer(Path lakeRoot) {
        this.lakeRoot = lakeRoot;
    }

    /**
     * @param emptyDocs documentos sem texto aproveitável (página só com links, PDF escaneado…)
     * @param sample    começo do primeiro trecho do documento mais recente, para conferir a extração
     */
    public record IssuerStats(String issuer, long docs, long attachments, long chunks, long emptyDocs, long avgChars,
                              String sample) {
    }

    public record Report(long docs, long chunks, long emptyDocs, List<String> failures, List<IssuerStats> issuers,
                         Path output) {
    }

    private record Doc(Path file, String sha, String contentType, String feedId, String issuer, String currency,
                       String market, String docType, String url, String title, String publishedAt,
                       String firstSeenAt, String parentUrl) {
    }

    public Report run(LakeSql sql, int maxChars) {
        Path out = lakeRoot.resolve("silver").resolve(TABLE);
        Path dir = Bronze.dir(lakeRoot, SOURCE);
        if (!Files.isDirectory(dir)) return new Report(0, 0, 0, List.of(), List.of(), out);

        List<Doc> docs = sql.query("""
                SELECT filename, sha256, content_type, feed_id, issuer, currency, coalesce(market, 'fx'), doc_type,
                       url, title, published_at, first_seen_at, parent_url
                  FROM read_json('%s/date=*/*.meta.json', filename = true,
                                 columns = {sha256: 'VARCHAR', content_type: 'VARCHAR', feed_id: 'VARCHAR',
                                            issuer: 'VARCHAR', currency: 'VARCHAR', market: 'VARCHAR',
                                            doc_type: 'VARCHAR', url: 'VARCHAR', title: 'VARCHAR',
                                            published_at: 'VARCHAR', first_seen_at: 'VARCHAR', parent_url: 'VARCHAR'})
                """.formatted(LakeSql.slashes(dir)), rs -> {
            Path meta = Path.of(rs.getString(1));
            String base = meta.getFileName().toString().replace(".meta.json", "");
            String type = rs.getString(3);
            if (type == null) type = guessType(meta.resolveSibling(base));   // documentos do passo 1
            return new Doc(meta.resolveSibling(base + "." + type), rs.getString(2), type, rs.getString(4),
                    rs.getString(5), rs.getString(6), rs.getString(7), rs.getString(8), rs.getString(9),
                    rs.getString(10), rs.getString(11), rs.getString(12), rs.getString(13));
        });

        List<Object[]> rows = new ArrayList<>();
        List<String> failures = new ArrayList<>();
        for (Doc d : docs) {
            String text;
            try {
                text = DocumentText.extract(Files.readAllBytes(d.file()), d.contentType());
            } catch (IOException | RuntimeException e) {
                failures.add(d.url() + ": " + e.getMessage());
                continue;
            }
            List<String> chunks = Chunker.split(text, maxChars);
            if (chunks.isEmpty()) chunks = List.of("");   // documento vazio continua visível no silver
            for (int i = 0; i < chunks.size(); i++) {
                String c = chunks.get(i);
                rows.add(new Object[]{d.sha(), d.url(), d.parentUrl(), d.contentType(), d.feedId(), d.issuer(),
                        d.currency(), d.market(), d.docType(), d.title(), d.publishedAt(), d.firstSeenAt(), i,
                        chunks.size(), c, c.length(),
                        LocalDiskLakeStorage.sha256(c.getBytes(StandardCharsets.UTF_8))});
            }
        }

        if (rows.isEmpty()) return new Report(0, 0, 0, failures, List.of(), out);
        sql.execute("""
                CREATE OR REPLACE TEMP TABLE doc_raw (doc_sha VARCHAR, url VARCHAR, parent_url VARCHAR,
                    content_type VARCHAR, feed_id VARCHAR, issuer VARCHAR, currency VARCHAR, market VARCHAR,
                    doc_type VARCHAR, title VARCHAR, published_at VARCHAR, first_seen_at VARCHAR, chunk_idx INTEGER,
                    n_chunks INTEGER, text VARCHAR, chars INTEGER, text_sha VARCHAR)
                """);
        sql.batch("INSERT INTO doc_raw VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)", rows);
        sql.execute("""
                CREATE OR REPLACE TEMP TABLE doc_chunks AS
                SELECT r.doc_sha, p.doc_sha AS parent_sha, r.url, r.parent_url, r.content_type, r.feed_id, r.issuer,
                       r.currency, r.market, r.doc_type, r.title,
                       CAST(replace(r.published_at, 'Z', '') AS TIMESTAMP) AS published_at,
                       CAST(replace(r.first_seen_at, 'Z', '') AS TIMESTAMP) AS first_seen_at,
                       r.chunk_idx, r.n_chunks, r.text, r.chars, r.text_sha
                  FROM doc_raw r
                  LEFT JOIN (SELECT url, min(doc_sha) AS doc_sha FROM doc_raw GROUP BY url) p ON p.url = r.parent_url
                """);

        Path tmp = out.resolveSibling(TABLE + ".tmp");
        LakeSql.deleteRecursively(tmp);
        sql.execute("""
                COPY (SELECT *, year(first_seen_at) AS year FROM doc_chunks
                       ORDER BY first_seen_at, doc_sha, chunk_idx)
                  TO %s (FORMAT PARQUET, COMPRESSION ZSTD, PARTITION_BY (market, year))
                """.formatted(LakeSql.literal(tmp)));
        LakeSql.replaceDirectory(tmp, out);

        List<IssuerStats> issuers = sql.query("""
                WITH d AS (
                    SELECT issuer, doc_sha, any_value(parent_url) IS NOT NULL AS is_attachment,
                           sum(chars) AS chars, count(*) AS chunks, max(first_seen_at) AS seen,
                           any_value(text) FILTER (WHERE chunk_idx = 0) AS first_text
                      FROM doc_chunks GROUP BY issuer, doc_sha)
                SELECT issuer, count(*), count(*) FILTER (WHERE is_attachment), sum(chunks),
                       count(*) FILTER (WHERE chars < 200), CAST(avg(chars) AS BIGINT),
                       left(arg_max(first_text, seen), 160)
                  FROM d GROUP BY issuer ORDER BY issuer
                """, rs -> new IssuerStats(rs.getString(1), rs.getLong(2), rs.getLong(3), rs.getLong(4),
                rs.getLong(5), rs.getLong(6), rs.getString(7)));
        long docCount = sql.scalar("SELECT count(DISTINCT doc_sha) FROM doc_chunks");
        long chunkCount = sql.scalar("SELECT count(*) FROM doc_chunks WHERE chars > 0");
        long empty = issuers.stream().mapToLong(IssuerStats::emptyDocs).sum();
        return new Report(docCount, chunkCount, empty, failures, issuers, out);
    }

    private static String guessType(Path withoutExt) {
        for (String ext : new String[]{"html", "pdf", "txt"}) {
            if (Files.exists(withoutExt.resolveSibling(withoutExt.getFileName() + "." + ext))) return ext;
        }
        return "html";
    }
}
