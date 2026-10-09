package com.jevforex.normalize;

import com.jevforex.lake.LakeSql;
import com.jevforex.lake.LocalDiskLakeStorage;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * bronze/cb_web + bronze/bis_speeches → silver/documents/market=…/year=…/*.parquet: uma linha por TRECHO.
 *
 * <p>O texto é dividido em trechos de até ~6.000 caracteres respeitando parágrafos: é o que o Jev avalia
 * (passo 4). Cada trecho carrega {@code available_utc}, o momento em que pode entrar no backtest:</p>
 * <ul>
 *   <li>cb_web (coletor próprio): o first_seen_at real;</li>
 *   <li>bis_speeches (acervo do BIS): fim do dia da publicação no BIS ({@code availability_estimated}).</li>
 * </ul>
 * <p>Um discurso que veio pelas duas fontes (mesma moeda, título parecido, publicação a até 21 dias) entra
 * uma vez só: a cópia disponível primeiro. Coletado ao vivo, o do coletor (horário real); coletado na primeira
 * carga do feed, meses depois de publicado, o do BIS.</p>
 */
public final class DocumentNormalizer {

    public static final String SOURCE = "cb_web";
    public static final String TABLE = "documents";
    private static final Duration DUPLICATE_WINDOW = Duration.ofDays(21);
    private static final double DUPLICATE_SIMILARITY = 0.5;
    /** Anexo com ≥ 80% das palavras presentes na página de origem = a mesma coisa em PDF. */
    private static final double SAME_CONTENT = 0.8;
    private static final int MIN_PARENT_CHARS = 1000;

    private final Path lakeRoot;

    public DocumentNormalizer(Path lakeRoot) {
        this.lakeRoot = lakeRoot;
    }

    /**
     * @param emptyDocs documentos sem texto aproveitável (página só com links, PDF escaneado…)
     * @param sample    começo do primeiro trecho do documento mais recente, para conferir a extração
     */
    public record IssuerStats(String source, String issuer, long docs, long attachments, long chunks, long emptyDocs,
                              long avgChars, String firstAvailable, String sample) {
    }

    /**
     * @param bisRecords        discursos lidos dos zips do BIS (todas as instituições)
     * @param bisOtherInstitutions de instituições fora da lista (ficam de fora)
     * @param bisDuplicates     discursos do BIS que o coletor próprio já tinha
     */
    public record Report(long docs, long chunks, long emptyDocs, List<String> failures, List<IssuerStats> issuers,
                         long bisRecords, long bisOtherInstitutions, long bisDuplicates, long duplicateAttachments,
                         Path output) {
    }

    /**
     * Um documento de qualquer fonte, com o texto calculado só quando for usado.
     *
     * @param release regra do horário de divulgação (só documentos do arquivo histórico; null nos demais)
     */
    private record Doc(String sha, String url, String parentUrl, String contentType, String source, String feedId,
                       String issuer, String currency, String market, String docType, String title,
                       String publishedAt, String firstSeenAt, String availableUtc, boolean estimated,
                       Release release, Supplier<String> text) {

        Doc withAvailability(String available, boolean est) {
            return new Doc(sha, url, parentUrl, contentType, source, feedId, issuer, currency, market, docType, title,
                    publishedAt, firstSeenAt, available, est, release, text);
        }
    }

    /** Comunicado/ata do arquivo histórico: o horário vem do calendário ({@link ReleaseResolver}). */
    private record Release(String kind, java.time.LocalDate refDate, String event, String anchor) {
    }

    public Report run(LakeSql sql, int maxChars) {
        Path out = lakeRoot.resolve("silver").resolve(TABLE);
        List<Doc> web = withReleaseTimes(webDocuments(sql), archiveIndex(), ReleaseResolver.load(sql, lakeRoot));
        List<Doc> docs = new ArrayList<>(web);
        Set<String> droppedWeb = new HashSet<>();
        BisSpeeches.Load bis = BisSpeeches.read(lakeRoot);
        int duplicates = 0;
        for (BisSpeeches.Speech s : bis.speeches()) {
            Doc same = sameSpeech(s, web);
            if (same != null) {
                duplicates++;
                // fica a cópia disponível primeiro ("vale o primeiro momento visto")
                if (!Instant.parse(same.availableUtc()).isAfter(s.availableUtc())) continue;
                droppedWeb.add(same.sha());
            }
            docs.add(new Doc(LocalDiskLakeStorage.sha256(s.url().getBytes(StandardCharsets.UTF_8)), s.url(), null,
                    "txt", BisSpeeches.SOURCE, "bis-speeches", s.issuer().name(), s.issuer().currency(), "fx",
                    "cb_text", s.title(), s.speechDate() == null ? null : s.speechDate() + "T00:00:00Z",
                    s.downloadedAt().toString(), s.availableUtc().toString(), true, null,
                    () -> DocumentText.tidy(s.text() == null ? "" : s.text())));
        }

        // texto de cada documento (uma vez só: o anexo é comparado com a página de origem)
        List<String> failures = new ArrayList<>();
        java.util.Map<String, String> textByUrl = new java.util.HashMap<>();
        java.util.Map<String, String> textBySha = new java.util.HashMap<>();
        for (Doc d : docs) {
            if (SOURCE.equals(d.source()) && droppedWeb.contains(d.sha())) continue;
            try {
                String t = d.text().get();
                textBySha.put(d.sha() + "|" + d.url(), t);
                textByUrl.put(d.url(), t);
            } catch (RuntimeException e) {
                failures.add(d.url() + ": " + e.getMessage());
            }
        }

        List<Object[]> rows = new ArrayList<>();
        int duplicateAttachments = 0;
        for (Doc d : docs) {
            String text = textBySha.get(d.sha() + "|" + d.url());
            if (text == null) continue;
            // o PDF que é só a própria página em outro formato (ex.: ata do FOMC) não entra duas vezes
            String parentText = d.parentUrl() == null ? null : textByUrl.get(d.parentUrl());
            if (parentText != null && parentText.length() >= MIN_PARENT_CHARS
                    && similarity(words(text), words(parentText)) >= SAME_CONTENT) {
                duplicateAttachments++;
                continue;
            }
            List<String> chunks = Chunker.split(text, maxChars);
            if (chunks.isEmpty()) chunks = List.of("");   // documento vazio continua visível no silver
            for (int i = 0; i < chunks.size(); i++) {
                String c = chunks.get(i);
                rows.add(new Object[]{d.sha(), d.url(), d.parentUrl(), d.contentType(), d.source(), d.feedId(),
                        d.issuer(), d.currency(), d.market(), d.docType(), d.title(), d.publishedAt(),
                        d.firstSeenAt(), d.availableUtc(), d.estimated(), i, chunks.size(), c, c.length(),
                        LocalDiskLakeStorage.sha256(c.getBytes(StandardCharsets.UTF_8))});
            }
        }
        if (rows.isEmpty()) {
            return new Report(0, 0, 0, failures, List.of(), bis.records(), bis.otherInstitutions(), duplicates,
                    duplicateAttachments, out);
        }

        LinkedHashMap<String, String> cols = new LinkedHashMap<>();
        for (String c : List.of("doc_sha", "url", "parent_url", "content_type", "source", "feed_id", "issuer",
                "currency", "market", "doc_type", "title", "published_at", "first_seen_at", "available_utc")) {
            cols.put(c, "VARCHAR");
        }
        cols.put("availability_estimated", "BOOLEAN");
        cols.put("chunk_idx", "INTEGER");
        cols.put("n_chunks", "INTEGER");
        cols.put("text", "VARCHAR");
        cols.put("chars", "INTEGER");
        cols.put("text_sha", "VARCHAR");
        // o acervo do BIS tem dezenas de milhares de trechos: passa por arquivo, não por INSERT no JDBC
        sql.createTableFromRows("doc_raw", cols, rows, lakeRoot.resolve("tmp"));
        sql.execute("""
                CREATE OR REPLACE TEMP TABLE doc_chunks AS
                SELECT r.doc_sha, p.doc_sha AS parent_sha, r.url, r.parent_url, r.content_type, r.source, r.feed_id,
                       r.issuer, r.currency, r.market, r.doc_type, r.title,
                       CAST(replace(r.published_at, 'Z', '') AS TIMESTAMP) AS published_at,
                       CAST(replace(r.first_seen_at, 'Z', '') AS TIMESTAMP) AS first_seen_at,
                       CAST(replace(r.available_utc, 'Z', '') AS TIMESTAMP) AS available_utc,
                       r.availability_estimated, r.chunk_idx, r.n_chunks, r.text, r.chars, r.text_sha
                  FROM doc_raw r
                  LEFT JOIN (SELECT url, min(doc_sha) AS doc_sha FROM doc_raw GROUP BY url) p ON p.url = r.parent_url
                """);
        sql.execute("DROP TABLE doc_raw");

        Path tmp = out.resolveSibling(TABLE + ".tmp");
        LakeSql.deleteRecursively(tmp);
        sql.execute("""
                COPY (SELECT *, year(available_utc) AS year FROM doc_chunks
                       ORDER BY available_utc, doc_sha, chunk_idx)
                  TO %s (FORMAT PARQUET, COMPRESSION ZSTD, PARTITION_BY (market, year))
                """.formatted(LakeSql.literal(tmp)));
        LakeSql.replaceDirectory(tmp, out);

        List<IssuerStats> issuers = sql.query("""
                WITH d AS (
                    SELECT source, issuer, doc_sha, any_value(parent_url) IS NOT NULL AS is_attachment,
                           sum(chars) AS chars, count(*) AS chunks, max(available_utc) AS avail,
                           min(available_utc) AS first_avail,
                           any_value(text) FILTER (WHERE chunk_idx = 0) AS first_text
                      FROM doc_chunks GROUP BY source, issuer, doc_sha)
                SELECT source, issuer, count(*), count(*) FILTER (WHERE is_attachment), sum(chunks),
                       count(*) FILTER (WHERE chars < 200), CAST(avg(chars) AS BIGINT),
                       CAST(CAST(min(first_avail) AS DATE) AS VARCHAR), left(arg_max(first_text, avail), 160)
                  FROM d GROUP BY source, issuer ORDER BY source, issuer
                """, rs -> new IssuerStats(rs.getString(1), rs.getString(2), rs.getLong(3), rs.getLong(4),
                rs.getLong(5), rs.getLong(6), rs.getLong(7), rs.getString(8), rs.getString(9)));
        long docCount = sql.scalar("SELECT count(DISTINCT doc_sha) FROM doc_chunks");
        long chunkCount = sql.scalar("SELECT count(*) FROM doc_chunks WHERE chars > 0");
        long empty = issuers.stream().mapToLong(IssuerStats::emptyDocs).sum();
        return new Report(docCount, chunkCount, empty, failures, issuers, bis.records(), bis.otherInstitutions(),
                duplicates, duplicateAttachments, out);
    }

    /** Páginas e PDFs do coletor próprio (bronze/cb_web). */
    private List<Doc> webDocuments(LakeSql sql) {
        Path dir = Bronze.dir(lakeRoot, SOURCE);
        if (!Files.isDirectory(dir)) return List.of();
        return sql.query("""
                SELECT filename, sha256, content_type, feed_id, issuer, currency, coalesce(market, 'fx'), doc_type,
                       url, title, published_at, first_seen_at, parent_url,
                       doc_kind, ref_date, release_event, release_anchor
                  FROM read_json('%s/date=*/*.meta.json', filename = true,
                                 columns = {sha256: 'VARCHAR', content_type: 'VARCHAR', feed_id: 'VARCHAR',
                                            issuer: 'VARCHAR', currency: 'VARCHAR', market: 'VARCHAR',
                                            doc_type: 'VARCHAR', url: 'VARCHAR', title: 'VARCHAR',
                                            published_at: 'VARCHAR', first_seen_at: 'VARCHAR', parent_url: 'VARCHAR',
                                            doc_kind: 'VARCHAR', ref_date: 'VARCHAR', release_event: 'VARCHAR',
                                            release_anchor: 'VARCHAR'})
                """.formatted(LakeSql.slashes(dir)), rs -> {
            Release release = rs.getString(15) == null ? null : new Release(rs.getString(14),
                    java.time.LocalDate.parse(rs.getString(15)), rs.getString(16), rs.getString(17));
            Path meta = Path.of(rs.getString(1));
            String base = meta.getFileName().toString().replace(".meta.json", "");
            String type = rs.getString(3);
            if (type == null) type = guessType(meta.resolveSibling(base));   // documentos do passo 1
            Path file = meta.resolveSibling(base + "." + type);
            String contentType = type;
            String firstSeen = rs.getString(12);
            return new Doc(rs.getString(2), rs.getString(9), rs.getString(13), contentType, SOURCE, rs.getString(4),
                    rs.getString(5), rs.getString(6), rs.getString(7), rs.getString(8), rs.getString(10),
                    rs.getString(11), firstSeen, firstSeen, false, release, () -> {
                try {
                    return DocumentText.extract(Files.readAllBytes(file), contentType);
                } catch (IOException e) {
                    throw new IllegalStateException(e.getMessage(), e);
                }
            });
        });
    }

    /**
     * Documentos do arquivo histórico: disponível no horário oficial de divulgação (calendário) + 30 s; sem
     * evento correspondente, no fim do dia de referência. PDFs anexos herdam o horário da página.
     * Sempre {@code availability_estimated} (não foi visto ao vivo).
     */
    private List<Doc> withReleaseTimes(List<Doc> web, java.util.Map<String, Release> index, ReleaseResolver resolver) {
        java.util.Map<String, Doc> byUrl = new java.util.HashMap<>();
        List<Doc> out = new ArrayList<>(web.size());
        for (Doc d0 : web) {
            // a regra vem do .meta.json (baixado pelo arquivo) ou do índice (já tinha vindo por um feed)
            Release rel = d0.release() != null ? d0.release() : index.get(d0.url());
            Doc d = rel == d0.release() ? d0 : new Doc(d0.sha(), d0.url(), d0.parentUrl(), d0.contentType(),
                    d0.source(), d0.feedId(), d0.issuer(), d0.currency(), d0.market(), d0.docType(), d0.title(),
                    d0.publishedAt(), d0.firstSeenAt(), d0.availableUtc(), d0.estimated(), rel, d0.text());
            Doc r = d;
            if (rel != null) {
                String when = resolver.resolve(rel.event(), rel.anchor(), rel.refDate())
                        .map(Instant::toString)
                        .orElse(rel.refDate().atTime(23, 59, 59).toInstant(java.time.ZoneOffset.UTC).toString());
                // documento visto ao vivo ANTES do horário estimado continua valendo pelo horário real
                if (Instant.parse(when).isBefore(Instant.parse(d.firstSeenAt()))) r = d.withAvailability(when, true);
            }
            byUrl.put(r.url(), r);
            out.add(r);
        }
        for (int i = 0; i < out.size(); i++) {
            Doc d = out.get(i);
            Doc parent = d.parentUrl() == null ? null : byUrl.get(d.parentUrl());
            if (parent != null && parent.release() != null) out.set(i, d.withAvailability(parent.availableUtc(), true));
        }
        return out;
    }

    /** bronze/cb_archive_index: url → regra do horário de divulgação (gravado pelo backfill-archives). */
    private java.util.Map<String, Release> archiveIndex() {
        java.util.Map<String, Release> out = new java.util.HashMap<>();
        Path dir = lakeRoot.resolve("bronze").resolve("cb_archive_index");
        if (!Files.isDirectory(dir)) return out;
        var json = new com.fasterxml.jackson.databind.ObjectMapper();
        try (var files = Files.walk(dir, 2)) {
            for (Path f : files.filter(p -> p.toString().endsWith(".json") && !p.toString().endsWith(".meta.json")).toList()) {
                for (var n : json.readTree(f.toFile())) {
                    out.put(n.path("url").asText(), new Release(n.path("doc_kind").asText(),
                            java.time.LocalDate.parse(n.path("ref_date").asText()), n.path("release_event").asText(),
                            n.path("release_anchor").asText()));
                }
            }
        } catch (IOException e) {
            throw new java.io.UncheckedIOException("Índice do arquivo ilegível em " + dir, e);
        }
        return out;
    }

    /**
     * O mesmo discurso também veio pelo coletor próprio? Mesma moeda, título parecido e data de PUBLICAÇÃO
     * próxima (o coletor pode ter visto o item meses depois, na primeira carga do feed).
     */
    static Doc sameSpeech(BisSpeeches.Speech s, List<Doc> web) {
        Set<String> bisWords = words(stripSpeaker(s.title()));
        if (bisWords.isEmpty()) return null;
        Instant when = s.availableUtc();
        for (Doc d : web) {
            if (d.parentUrl() != null || !s.issuer().currency().equals(d.currency())) continue;
            Instant published = Instant.parse(d.publishedAt() != null ? d.publishedAt() : d.firstSeenAt());
            if (Duration.between(published, when).abs().compareTo(DUPLICATE_WINDOW) > 0) continue;
            if (similarity(bisWords, words(d.title())) >= DUPLICATE_SIMILARITY) return d;
        }
        return null;
    }

    /** "Michelle W Bowman: New year's resolutions…" → "New year's resolutions…" */
    static String stripSpeaker(String title) {
        if (title == null) return "";
        int colon = title.indexOf(": ");
        return colon > 0 && colon < 60 ? title.substring(colon + 2) : title;
    }

    static Set<String> words(String s) {
        if (s == null) return Set.of();
        return Arrays.stream(s.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}]+"))
                .filter(w -> w.length() >= 3).collect(Collectors.toCollection(HashSet::new));
    }

    /** Fração das palavras do título do BIS que aparecem no título do coletor. */
    static double similarity(Set<String> bis, Set<String> web) {
        if (bis.isEmpty()) return 0;
        long common = bis.stream().filter(web::contains).count();
        return (double) common / bis.size();
    }

    private static String guessType(Path withoutExt) {
        for (String ext : new String[]{"html", "pdf", "txt"}) {
            if (Files.exists(withoutExt.resolveSibling(withoutExt.getFileName() + "." + ext))) return ext;
        }
        return "html";
    }
}
