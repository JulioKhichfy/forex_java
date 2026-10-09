package com.jevforex.normalize;

import com.jevforex.lake.LakeProperties;
import com.jevforex.lake.LakeSql;
import com.jevforex.lake.LocalDiskLakeStorage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ReleaseResolverTest {

    private final ReleaseResolver r = ReleaseResolver.of(Map.of(
            "fomc-meeting-statement", List.of(LocalDateTime.parse("2024-01-31T19:00:00")),
            "fomc-minutes", List.of(LocalDateTime.parse("2024-01-03T19:00:00"), LocalDateTime.parse("2024-02-21T19:00:00")),
            "boj-monetary-policy-statement", List.of(LocalDateTime.parse("2024-01-23T03:00:00"),
                    LocalDateTime.parse("2024-03-19T04:00:00")),
            "boj-monetary-policy-meeting-minutes", List.of(LocalDateTime.parse("2024-02-28T23:50:00"),
                    LocalDateTime.parse("2024-03-24T23:50:00"))));

    @Test
    void regras() {
        assertEquals(Optional.of(Instant.parse("2024-01-31T19:00:30Z")),        // horário oficial + 30 s
                r.resolve("fomc-meeting-statement", "same_day", LocalDate.parse("2024-01-31")));
        assertEquals(Optional.of(Instant.parse("2024-02-21T19:00:30Z")),        // ata: 3 semanas depois
                r.resolve("fomc-minutes", "first_after", LocalDate.parse("2024-01-31")));
        // ata do BoJ da reunião de 23/01: sai depois da reunião seguinte (19/03), não em 28/02
        assertEquals(Optional.of(Instant.parse("2024-03-24T23:50:30Z")),
                r.resolve("boj-monetary-policy-meeting-minutes", "after_next:boj-monetary-policy-statement",
                        LocalDate.parse("2024-01-23")));
        assertEquals(Optional.empty(), r.resolve("evento-inexistente", "same_day", LocalDate.parse("2024-01-31")));
    }

    @Test
    void normalizador_arquivoUsaOCalendario_anexoHerda(@TempDir Path lakeRoot) throws Exception {
        LocalDiskLakeStorage lake = new LocalDiskLakeStorage(new LakeProperties(lakeRoot.toString()));
        try (LakeSql sql = LakeSql.open(lakeRoot.resolve("tmp"), "1GB")) {
            sql.execute("COPY (SELECT 'fomc-minutes' AS event_code, TIMESTAMP '2024-02-21 19:00:00' AS scheduled_utc, "
                    + "'fx' AS market, 2024 AS year) TO " + LakeSql.literal(lakeRoot.resolve("silver/calendar_events"))
                    + " (FORMAT PARQUET, PARTITION_BY (market, year))");
        }
        String page = "https://www.federalreserve.gov/monetarypolicy/fomcminutes20240131.htm";
        Map<String, Object> meta = new LinkedHashMap<>(Map.of("feed_id", "fed-archive", "issuer", "Federal Reserve",
                "currency", "USD", "market", "fx", "doc_type", "cb_text", "url", page, "content_type", "html",
                "title", "Minutes of the FOMC meeting of 2024-01-31"));
        meta.putAll(Map.of("doc_kind", "minutes", "ref_date", "2024-01-31", "release_event", "fomc-minutes",
                "release_anchor", "first_after"));
        lake.writeBronze("cb_web", Instant.parse("2026-10-09T18:00:00Z"), "html",   // baixado em 2026…
                "<div id=\"article\"><p>Participants agreed inflation remained elevated.</p></div>"
                        .getBytes(StandardCharsets.UTF_8), meta);
        lake.writeBronze("cb_web", Instant.parse("2026-10-09T18:00:05Z"), "txt",
                "Anexo da ata.".getBytes(StandardCharsets.UTF_8),
                Map.of("feed_id", "fed-archive", "issuer", "Federal Reserve", "currency", "USD", "market", "fx",
                        "doc_type", "cb_text", "url", "https://www.federalreserve.gov/x/minutes.pdf",
                        "content_type", "txt", "title", "Anexo", "parent_url", page));

        try (LakeSql sql = LakeSql.open(lakeRoot.resolve("tmp"), "1GB")) {
            DocumentNormalizer.Report rep = new DocumentNormalizer(lakeRoot).run(sql, 6000);
            List<String> rows = sql.query("SELECT CAST(available_utc AS VARCHAR) || ' ' || availability_estimated "
                    + "FROM read_parquet('" + LakeSql.slashes(rep.output()) + "/**/*.parquet', hive_partitioning = true) "
                    + "ORDER BY url DESC", rs -> rs.getString(1));
            // …mas disponível no horário oficial da ata (21/02/2024 19:00 + 30 s); o anexo herda
            assertEquals(List.of("2024-02-21 19:00:30 true", "2024-02-21 19:00:30 true"), rows);
        }
    }

    @Test
    void documentoQueVeioPeloFeed_usaORegraDoIndice(@TempDir Path lakeRoot) throws Exception {
        LocalDiskLakeStorage lake = new LocalDiskLakeStorage(new LakeProperties(lakeRoot.toString()));
        try (LakeSql sql = LakeSql.open(lakeRoot.resolve("tmp"), "1GB")) {
            sql.execute("COPY (SELECT 'boc-interest-rate-decision' AS event_code, TIMESTAMP '2026-09-09 13:45:00' "
                    + "AS scheduled_utc, 'fx' AS market, 2026 AS year) TO "
                    + LakeSql.literal(lakeRoot.resolve("silver/calendar_events")) + " (FORMAT PARQUET, PARTITION_BY (market, year))");
        }
        String url = "https://www.bankofcanada.ca/2026/09/fad-press-release-2026-09-09/";
        // veio pelo feed na primeira carga (09/10/2026), sem a regra no .meta.json
        lake.writeBronze("cb_web", Instant.parse("2026-10-09T15:00:00Z"), "html",
                "<main><p>Bank of Canada maintains policy rate.</p></main>".getBytes(StandardCharsets.UTF_8),
                Map.of("feed_id", "boc-press", "issuer", "Bank of Canada", "currency", "CAD", "market", "fx",
                        "doc_type", "cb_text", "url", url, "content_type", "html", "title", "Rate announcement"));
        // o backfill-archives listou a mesma URL e gravou o índice
        lake.writeBronze("cb_archive_index", Instant.parse("2026-10-09T16:00:00Z"), "json",
                ("[{\"url\":\"" + url + "\",\"doc_kind\":\"statement\",\"ref_date\":\"2026-09-09\","
                        + "\"release_event\":\"boc-interest-rate-decision\",\"release_anchor\":\"same_day\"}]")
                        .getBytes(StandardCharsets.UTF_8), Map.of("bank", "boc"));

        try (LakeSql sql = LakeSql.open(lakeRoot.resolve("tmp"), "1GB")) {
            DocumentNormalizer.Report rep = new DocumentNormalizer(lakeRoot).run(sql, 6000);
            assertEquals(List.of("2026-09-09 13:45:30"), sql.query("SELECT CAST(available_utc AS VARCHAR) FROM "
                    + "read_parquet('" + LakeSql.slashes(rep.output()) + "/**/*.parquet', hive_partitioning = true)",
                    rs -> rs.getString(1)));
        }
    }
}
