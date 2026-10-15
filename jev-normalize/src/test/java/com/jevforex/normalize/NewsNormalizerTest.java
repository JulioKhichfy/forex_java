package com.jevforex.normalize;

import com.jevforex.lake.LakeSql;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class NewsNormalizerTest {

    @Test
    void aceitacaoDaSec_horarioDeNovaYork_comEsemHorarioDeVerao() {
        // 16:30 em Nova York: 20:30 UTC no verão (EDT, −4 h) e 21:30 UTC no inverno (EST, −5 h)
        assertEquals(Instant.parse("2026-07-30T20:30:28Z"),
                NewsNormalizer.accepted("<ACCEPTANCE-DATETIME>20260730163028\nACCEPTANCE-DATETIME: 20260730163028"));
        assertEquals(Instant.parse("2026-01-29T21:30:00Z"), NewsNormalizer.accepted("ACCEPTANCE-DATETIME:\t20260129163000"));
        assertEquals(Instant.parse("2024-03-08T13:58:04Z"), NewsNormalizer.accepted("&lt;ACCEPTANCE-DATETIME&gt;20240308085804"),
                "8-K/A da MSFT: 08:58 em Nova York (inverno) = 13:58 UTC, o mesmo que o JSON da SEC");
        assertNull(NewsNormalizer.accepted("sem cabeçalho"));
    }

    @Test
    void manchetes_umaPorUrl_primeiroLote_aoVivo_eSec(@TempDir Path lake) throws IOException {
        Path d = lake.resolve("bronze/gdelt_gkg/date=2026-10-14");
        Files.createDirectories(d);
        String fed = "{\"record_id\": \"1\", \"batch\": \"20261014120000\", \"domain\": \"cnbc.com\", \"url\": \"https://x/1\", "
                + "\"themes\": \"ECON_CENTRALBANK;ECON_INTEREST_RATES;\", \"persons\": \"jerome powell\", \"organizations\": \"\", "
                + "\"tone\": -1.5, \"positive\": 1.0, \"negative\": 2.5, \"polarity\": 3.5, \"title\": \"Fed \\\"holds\\\" rates\"}";
        // lote das 12:00 só no histórico; lote das 12:15 ao vivo (gravado às 12:40) traz a mesma notícia e uma nova
        gz(d, "a".repeat(64), "{\"batch\": \"20261014120000\"}\n" + fed + "\n",
                "{\"mode\": \"backfill\", \"first_seen_at\": \"2026-10-20T00:00:00Z\", \"rows_total\": 1500}");
        gz(d, "b".repeat(64), "{\"batch\": \"20261014121500\"}\n" + fed.replace("20261014120000", "20261014121500") + "\n"
                        + "{\"record_id\": \"2\", \"batch\": \"20261014121500\", \"domain\": \"apnews.com\", \"url\": \"https://x/2\", "
                        + "\"themes\": \"\", \"tone\": null, \"title\": \"CPI\"}\n",
                "{\"mode\": \"live\", \"first_seen_at\": \"2026-10-14T12:40:00Z\", \"rows_total\": 1400}");        Path e = lake.resolve("bronze/edgar_filings/date=2026-10-14");
        Files.createDirectories(e);
        // o formato real do -index-headers.html: a tag SGML, escapada e não escapada, e um ITEMS no meio
        Files.writeString(e.resolve("h.html"), "<ACCEPTANCE-DATETIME>20260730163028\n&lt;ACCEPTANCE-DATETIME&gt;20260730163028\n"
                + "ITEM INFORMATION:\t\t&lt;ITEMS&gt;2.02\n"
                + "ITEM INFORMATION:\t\tResults of Operations and Financial Condition\n"
                + "ITEM INFORMATION:\t\tFinancial Statements and Exhibits\n");
        Files.writeString(e.resolve("h.meta.json"), "{\"kind\": \"index-headers\", \"ticker\": \"AAPL\", \"cik\": 320193, "
                + "\"accession\": \"0000320193-26-000018\", \"form\": \"8-K\", \"items\": \"2.02,9.01\", "
                + "\"filing_date\": \"2026-07-30\", \"acceptance_json\": \"2026-07-31T00:30:28.000Z\"}");
        Files.writeString(e.resolve("p.html"), "<html>press release</html>");
        Files.writeString(e.resolve("p.meta.json"), "{\"kind\": \"primary\", \"accession\": \"0000320193-26-000018\"}");

        try (LakeSql sql = LakeSql.open(lake.resolve("tmp"), "1GB")) {
            var n = new NewsNormalizer(lake);
            var r = n.headlines(sql, 15);
            assertEquals(2, r.batches());
            assertEquals(2900, r.rowsTotal());
            assertEquals(3, r.rows());
            assertEquals(2, r.unique());
            assertEquals(2, r.liveSeen(), "as duas estão no lote gravado ao vivo");
            assertEquals(32.5, r.liveLagMinutesMedian(), 1e-9, "40 e 25 min do lote até a gravação");
            List<String> row = sql.query("""
                    SELECT title || '|' || CAST(seen_utc AS VARCHAR) || '|' || CAST(available_utc AS VARCHAR) || '|'
                           || origin || '|' || coalesce(array_to_string(themes, ','), '') || '|' || coalesce(CAST(tone AS VARCHAR), '-')
                      FROM read_parquet('%s/**/*.parquet', hive_partitioning = true) ORDER BY url
                    """.formatted(LakeSql.slashes(r.output())), rs -> rs.getString(1));
            // o Fed vale pelo lote das 12:00 (histórico: 12:00 + 15 min), mesmo o ao vivo só tendo visto às 12:40;
            // a CPI só existe no lote ao vivo: 12:15 + 15 min é antes da gravação (12:40), vale a gravação
            assertEquals(List.of("Fed \"holds\" rates|2026-10-14 12:00:00|2026-10-14 12:15:00|both|ECON_CENTRALBANK,ECON_INTEREST_RATES|-1.5",
                    "CPI|2026-10-14 12:15:00|2026-10-14 12:40:00|live||-"), row);
            var f = n.filings(sql);
            assertEquals(1, f.filings());
            assertEquals(1, f.withPrimary());
            assertEquals(Long.valueOf(1), f.jsonOffsetMinutes().get(240L), "diferença JSON − cabeçalho, em minutos (exemplo)");
            String s = sql.query("SELECT ticker || '|' || CAST(accepted_utc AS VARCHAR) || '|' || item_text FROM read_parquet("
                    + LakeSql.literal(f.output().resolve("part-0.parquet")) + ")", rs -> rs.getString(1)).get(0);
            assertEquals("AAPL|2026-07-30 20:30:28|Results of Operations and Financial Condition | Financial Statements "
                    + "and Exhibits", s);
        }
    }

    private static void gz(Path dir, String sha, String body, String meta) throws IOException {
        try (var o = new java.util.zip.GZIPOutputStream(Files.newOutputStream(dir.resolve(sha + ".jsonl.gz")))) {
            o.write(body.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
        Files.writeString(dir.resolve(sha + ".meta.json"), meta.replace("{", "{\"sha256\": \"" + sha + "\", "));
    }
}
