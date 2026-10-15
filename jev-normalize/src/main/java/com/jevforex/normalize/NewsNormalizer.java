package com.jevforex.normalize;

import com.fasterxml.jackson.core.json.JsonReadFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.jevforex.lake.LakeSql;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Bronze das notícias (passo 10a) → silver, reconstruído inteiro a cada execução:
 * <ul>
 *   <li>{@code silver/headlines}: uma linha por notícia do GDELT (por URL, a do primeiro lote em que apareceu), com
 *       título, site, temas e tom do GDELT e {@code available_utc} — a partir de quando o sistema pode usá-la;</li>
 *   <li>{@code silver/filings}: um documento da SEC por linha, com o horário oficial de aceitação em UTC.</li>
 * </ul>
 *
 * <p><b>available_utc das manchetes.</b> O GDELT publica um lote a cada 15 min; o horário do lote é quando a notícia
 * ficou pública ali. Lote do histórico: vale {@code lote + lag}; lote gravado ao vivo: o mais tarde entre isso e o
 * instante da gravação. A notícia vale pelo lote mais cedo. O relatório mostra o atraso real do ao vivo.</p>
 */
public final class NewsNormalizer {

    public static final String HEADLINES = "headlines";
    public static final String FILINGS = "filings";
    private static final ObjectMapper JSON = JsonMapper.builder()
            .enable(JsonReadFeature.ALLOW_UNESCAPED_CONTROL_CHARS)
            .enable(JsonReadFeature.ALLOW_BACKSLASH_ESCAPING_ANY_CHARACTER)
            .build();
    private static final ZoneId NEW_YORK = ZoneId.of("America/New_York");
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final Path lakeRoot;

    public NewsNormalizer(Path lakeRoot) {
        this.lakeRoot = lakeRoot;
    }

    /**
     * @param batches              lotes no bronze
     * @param rowsTotal            notícias que o GDELT leu nesses lotes (antes do filtro)
     * @param rows                 notícias guardadas (depois do filtro, com repetidas)
     * @param liveLagMinutesMedian atraso mediano (min) do lote até o coletor ao vivo gravá-lo; null sem ao vivo
     */
    public record HeadlineReport(long batches, long rowsTotal, long rows, long unique, long liveSeen,
                                 Double liveLagMinutesMedian, Double liveLagMinutesP90, String first, String last,
                                 Map<String, Long> byDomain, Path output) {
    }

    public record FilingReport(long filings, long withPrimary, Map<String, Long> byForm,
                               Map<Long, Long> jsonOffsetMinutes, Path output) {
    }

    // ------------------------------------------------------------------ manchetes (GDELT GKG)

    public HeadlineReport headlines(LakeSql sql, int lagMinutes) {
        return headlines(sql, lagMinutes, List.of());
    }

    /**
     * @param themePrefixes no silver ficam só os temas do GDELT que começam assim (vazio = todos). A lista completa
     *                      tem 1–2 KB por notícia e não cabe na memória com milhões de linhas. Pessoas e
     *                      organizações também ficam só no bronze, pelo mesmo motivo
     */
    public HeadlineReport headlines(LakeSql sql, int lagMinutes, List<String> themePrefixes) {
        Path dir = lakeRoot.resolve("bronze").resolve("gdelt_gkg");
        Path out = lakeRoot.resolve("silver").resolve(HEADLINES);
        Path tmp = out.resolveSibling(HEADLINES + ".tmp");
        LakeSql.deleteRecursively(tmp);
        tmp.toFile().mkdirs();
        if (metas("gdelt_gkg").isEmpty()) {
            LakeSql.replaceDirectory(tmp, out);
            return new HeadlineReport(0, 0, 0, 0, 0, null, null, null, null, Map.of(), out);
        }
        String glob = LakeSql.slashes(dir) + "/date=*/";
        sql.execute("""
                CREATE OR REPLACE TEMP TABLE gkg_meta AS
                SELECT sha256 AS sha, mode, CAST(replace(first_seen_at, 'Z', '') AS TIMESTAMP) AS collected,
                       rows_total
                  FROM read_json('%s*.meta.json', columns = {sha256: 'VARCHAR', mode: 'VARCHAR',
                                 first_seen_at: 'VARCHAR', rows_total: 'BIGINT'})
                """.formatted(glob));
        sql.execute("""
                CREATE OR REPLACE TEMP TABLE gkg_rows AS
                SELECT regexp_extract(filename, '([0-9a-f]{64})\\.jsonl\\.gz$', 1) AS sha,
                       strptime(batch, '%%Y%%m%%d%%H%%M%%S') AS b, record_id, domain, url, title,
                       %s AS themes, tone, positive, negative, polarity
                  FROM read_json('%s*.jsonl.gz', format = 'newline_delimited', filename = true,
                                 columns = {record_id: 'VARCHAR', batch: 'VARCHAR', domain: 'VARCHAR', url: 'VARCHAR',
                                            themes: 'VARCHAR',
                                            tone: 'DOUBLE', positive: 'DOUBLE', negative: 'DOUBLE',
                                            polarity: 'DOUBLE', title: 'VARCHAR'})
                 WHERE url IS NOT NULL
                """.formatted(themeFilter(themePrefixes), glob));
        // uma linha por URL: a do primeiro lote. Em duas etapas que o DuckDB pagina em disco (um GROUP BY com
        // arg_min de textos longos estourava a memória com 3,7 milhões de linhas): a primeira linha por ordenação e,
        // à parte, um resumo pequeno só com os instantes
        sql.execute("""
                CREATE OR REPLACE TEMP TABLE gkg_first AS
                SELECT r.* FROM gkg_rows r JOIN gkg_meta m USING (sha)
                QUALIFY row_number() OVER (PARTITION BY r.url ORDER BY r.b, m.mode) = 1
                """);
        sql.execute("""
                CREATE OR REPLACE TEMP TABLE gkg_when AS
                SELECT r.url,
                       -- cada lote vale pela própria regra (ao vivo: nunca antes de gravado); a notícia, pelo mais cedo
                       min(CASE WHEN m.mode = 'live' THEN greatest(r.b + INTERVAL %d MINUTE, m.collected)
                                ELSE r.b + INTERVAL %d MINUTE END) AS available_utc,
                       min(m.collected) FILTER (WHERE m.mode = 'live') AS first_live_utc,
                       bool_or(m.mode <> 'live') AS in_backfill
                  FROM gkg_rows r JOIN gkg_meta m USING (sha) GROUP BY r.url
                """.formatted(lagMinutes, lagMinutes));
        sql.execute("""
                CREATE OR REPLACE TEMP TABLE headlines_out AS
                SELECT f.url, f.title, f.domain, f.b AS seen_utc, w.available_utc, w.first_live_utc,
                       CASE WHEN w.first_live_utc IS NULL THEN 'backfill' WHEN w.in_backfill THEN 'both' ELSE 'live' END
                           AS origin,
                       f.themes, f.tone, f.positive, f.negative, f.polarity, f.record_id
                  FROM gkg_first f JOIN gkg_when w USING (url)
                """);
        sql.execute("DROP TABLE gkg_first");
        sql.execute("DROP TABLE gkg_when");        sql.execute("""
                COPY (SELECT *, year(seen_utc) AS year FROM headlines_out ORDER BY seen_utc)
                  TO %s (FORMAT PARQUET, COMPRESSION ZSTD, PARTITION_BY (year))
                """.formatted(LakeSql.literal(tmp.resolve("data"))));
        LakeSql.replaceDirectory(tmp, out);

        long batches = sql.scalar("SELECT count(*) FROM gkg_meta");
        long rowsTotal = sql.scalar("SELECT coalesce(sum(rows_total), 0) FROM gkg_meta");
        long rows = sql.scalar("SELECT count(*) FROM gkg_rows");
        long unique = sql.scalar("SELECT count(*) FROM headlines_out");
        long liveSeen = sql.scalar("SELECT count(*) FROM headlines_out WHERE first_live_utc IS NOT NULL");
        Double[] lag = sql.query("""
                SELECT quantile_cont(date_diff('second', seen_utc, first_live_utc) / 60.0, 0.5),
                       quantile_cont(date_diff('second', seen_utc, first_live_utc) / 60.0, 0.9)
                  FROM headlines_out WHERE first_live_utc IS NOT NULL
                """, rs -> new Double[]{(Double) rs.getObject(1), (Double) rs.getObject(2)}).get(0);
        String[] range = sql.query("SELECT CAST(min(seen_utc) AS VARCHAR), CAST(max(seen_utc) AS VARCHAR) FROM headlines_out",
                rs -> new String[]{rs.getString(1), rs.getString(2)}).get(0);
        Map<String, Long> byDomain = new LinkedHashMap<>();
        sql.query("SELECT domain, count(*) FROM headlines_out GROUP BY 1 ORDER BY 2 DESC LIMIT 12",
                rs -> byDomain.put(rs.getString(1), rs.getLong(2)));
        return new HeadlineReport(batches, rowsTotal, rows, unique, liveSeen, lag[0], lag[1], range[0], range[1],
                byDomain, out);
    }
    // ------------------------------------------------------------------ SEC

    /** "&lt;ACCEPTANCE-DATETIME&gt;20240308085804" (o cabeçalho SGML, escapado no HTML) ou "ACCEPTANCE-DATETIME: …". */
    private static final Pattern ACCEPTED = Pattern.compile("ACCEPTANCE-DATETIME(?:>|&gt;|:)\\s*(\\d{14})");
    private static final Pattern ITEM = Pattern.compile("ITEM INFORMATION:\\s*([^\\r\\n<&]+)");

    public FilingReport filings(LakeSql sql) {
        Map<String, String> primary = new HashMap<>();
        List<JsonNode> headers = new ArrayList<>();
        Map<String, Path> headerFiles = new HashMap<>();
        for (Path meta : metas("edgar_filings")) {
            JsonNode m = readJson(meta);
            Path body = meta.resolveSibling(meta.getFileName().toString().replace(".meta.json", ".html"));
            String acc = m.path("accession").asText();
            if ("primary".equals(m.path("kind").asText())) primary.put(acc, rel(body));
            else if ("index-headers".equals(m.path("kind").asText()) && !headerFiles.containsKey(acc)) {
                headers.add(m);
                headerFiles.put(acc, body);
            }
        }
        List<Object[]> rows = new ArrayList<>();
        Map<String, Long> byForm = new java.util.TreeMap<>();
        Map<Long, Long> offsets = new java.util.TreeMap<>();
        for (JsonNode m : headers) {
            String acc = m.path("accession").asText();
            String text = read(headerFiles.get(acc));
            Instant accepted = accepted(text);
            if (accepted == null) continue;
            Instant json = parseInstant(m.path("acceptance_json").asText(null));
            if (json != null) offsets.merge(Duration.between(accepted, json).toMinutes(), 1L, Long::sum);
            List<String> items = new ArrayList<>();
            Matcher im = ITEM.matcher(text);
            while (im.find()) if (!im.group(1).isBlank()) items.add(im.group(1).trim());
            String form = m.path("form").asText();
            byForm.merge(form, 1L, Long::sum);
            rows.add(new Object[]{"stocks", m.path("ticker").asText(), m.path("cik").asLong(), acc, form,
                    m.path("items").asText(""), String.join(" | ", items), m.path("filing_date").asText(),
                    ts(accepted), ts(accepted.plus(Duration.ofMinutes(1))), ts(json),
                    rel(headerFiles.get(acc)), primary.get(acc)});
        }
        LinkedHashMap<String, String> cols = new LinkedHashMap<>();
        cols.put("market", "VARCHAR");
        cols.put("ticker", "VARCHAR");
        cols.put("cik", "BIGINT");
        cols.put("accession", "VARCHAR");
        cols.put("form", "VARCHAR");
        cols.put("items", "VARCHAR");
        cols.put("item_text", "VARCHAR");
        cols.put("filing_date", "DATE");
        cols.put("accepted_utc", "TIMESTAMP");
        cols.put("available_utc", "TIMESTAMP");
        cols.put("acceptance_json_utc", "TIMESTAMP");
        cols.put("headers_path", "VARCHAR");
        cols.put("primary_path", "VARCHAR");
        Path out = lakeRoot.resolve("silver").resolve(FILINGS);
        sql.createTableFromRows("filing_rows", cols, rows, lakeRoot.resolve("tmp"));
        Path tmp = out.resolveSibling(FILINGS + ".tmp");
        LakeSql.deleteRecursively(tmp);
        tmp.toFile().mkdirs();
        sql.execute("COPY (SELECT * FROM filing_rows ORDER BY accepted_utc) TO "
                + LakeSql.literal(tmp.resolve("part-0.parquet")) + " (FORMAT PARQUET)");
        LakeSql.replaceDirectory(tmp, out);
        return new FilingReport(rows.size(), primary.size(), byForm, offsets, out);
    }

    /** Os temas como lista, só os dos prefixos pedidos (SQL). */
    static String themeFilter(List<String> prefixes) {
        String list = "list_filter(string_split(coalesce(themes, ''), ';'), x -> x <> '')";
        if (prefixes == null || prefixes.isEmpty()) return list;
        String cond = prefixes.stream().filter(p -> p.matches("[A-Za-z0-9_]+"))
                .map(p -> "starts_with(x, '" + p + "')").collect(java.util.stream.Collectors.joining(" OR "));
        return "list_distinct(list_filter(string_split(coalesce(themes, ''), ';'), x -> " + cond + "))";
    }

    /** ACCEPTANCE-DATETIME do cabeçalho da SEC: horário de Nova York → UTC. */
    static Instant accepted(String headerText) {
        Matcher mm = ACCEPTED.matcher(headerText);
        if (!mm.find()) return null;
        return LocalDateTime.parse(mm.group(1), DateTimeFormatter.ofPattern("yyyyMMddHHmmss"))
                .atZone(NEW_YORK).toInstant();
    }

    // ------------------------------------------------------------------ apoio

    private List<Path> metas(String source) {
        Path dir = lakeRoot.resolve("bronze").resolve(source);
        if (!Files.isDirectory(dir)) return List.of();
        try (Stream<Path> s = Files.walk(dir, 2)) {
            return s.filter(p -> p.getFileName().toString().endsWith(".meta.json")).sorted().toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static JsonNode readJson(Path p) {
        try {
            return JSON.readTree(p.toFile());
        } catch (IOException e) {
            throw new UncheckedIOException("JSON ilegível no bronze: " + p, e);
        }
    }

    private static String read(Path p) {
        try {
            return new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private String rel(Path p) {
        return lakeRoot.relativize(p).toString().replace('\\', '/');
    }

    private static Instant parseInstant(String s) {
        try {
            return s == null || s.isBlank() ? null : Instant.parse(s);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** "2026-10-14 12:30:00" (o read_json do DuckDB lê como TIMESTAMP). */
    private static String ts(Instant i) {
        return i == null ? null : TS.format(i.atZone(ZoneOffset.UTC));
    }
}
