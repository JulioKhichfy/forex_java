package com.jevforex.normalize;

import com.jevforex.lake.LakeProperties;
import com.jevforex.lake.LakeSql;
import com.jevforex.lake.LocalDiskLakeStorage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BisSpeechesTest {

    private static final String CSV = """
            url,title,description,date,text,author
            https://www.bis.org/review/r240109a.htm,"Michelle W Bowman: New year's resolutions for bank regulatory policymakers","Speech by Ms Michelle W Bowman, Member of the Board of Governors of the Federal Reserve System, at the South Carolina Bankers","2024-12-08 00:00:00","For release on delivery

            New Year's Resolutions. Inflation remains elevated, ""she said"".",Michelle W Bowman
            https://www.bis.org/review/r240215b.htm,"Joachim Nagel: Monetary policy at a crossroads","Speech by Dr Joachim Nagel, President of the Deutsche Bundesbank and Member of the Governing Council of the European Central Bank, at Frankfurt","2024-02-13 00:00:00","Price stability comes first.",Joachim Nagel
            https://www.bis.org/review/r240301c.htm,"Shaktikanta Das: Monetary policy in India","Speech by Mr Shaktikanta Das, Governor of the Reserve Bank of India, at Mumbai","2024-02-28 00:00:00","Text.",Shaktikanta Das
            https://www.bis.org/review/r240305a.htm,"Christopher J Waller: Some thoughts on r-star","Speech by Mr Christopher J Waller, Member of the Board of Governors of the Federal Reserve System, at New York","2024-03-04 00:00:00","R-star text.",Christopher J Waller
            """;

    private static byte[] zip(String name, String content) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream z = new ZipOutputStream(out)) {
            z.putNextEntry(new ZipEntry(name));
            z.write(content.getBytes(StandardCharsets.UTF_8));
            z.closeEntry();
        }
        return out.toByteArray();
    }

    @Test
    void dataDaUrl_emissorPelaDescricao_eDuplicataDoColetor(@TempDir Path lakeRoot) throws Exception {
        LocalDiskLakeStorage lake = new LocalDiskLakeStorage(new LakeProperties(lakeRoot.toString()));
        lake.writeBronze("bis_speeches", Instant.parse("2026-10-09T17:00:00Z"), "zip",
                zip("speeches_2024.csv", CSV), Map.of("year", 2024));
        // o coletor próprio já tinha o discurso do Waller (título no formato do feed do Fed)
        lake.writeBronze("cb_web", Instant.parse("2024-03-04T19:00:00Z"), "html",
                "<div id=\"article\"><p>R-star text.</p></div>".getBytes(StandardCharsets.UTF_8),
                Map.of("feed_id", "fed-speeches", "issuer", "Federal Reserve", "currency", "USD", "market", "fx",
                        "doc_type", "cb_text", "url", "https://www.federalreserve.gov/newsevents/speech/waller20240304a.htm",
                        "title", "Waller, Some Thoughts on r-star", "content_type", "html"));
        // primeira carga do feed: o coletor só viu em 2026 um discurso publicado em fevereiro de 2024
        lake.writeBronze("cb_web", Instant.parse("2026-10-09T15:00:00Z"), "html",
                "<div id=\"article\"><p>Price stability comes first.</p></div>".getBytes(StandardCharsets.UTF_8),
                Map.of("feed_id", "buba", "issuer", "Deutsche Bundesbank", "currency", "EUR", "market", "fx",
                        "doc_type", "cb_text", "url", "https://www.bundesbank.de/nagel-crossroads",
                        "title", "Monetary policy at a crossroads", "published_at", "2024-02-14T10:00:00Z",
                        "content_type", "html"));

        BisSpeeches.Load load = BisSpeeches.read(lakeRoot);
        assertEquals(4, load.records());
        assertEquals(1, load.otherInstitutions());               // Reserve Bank of India não está na lista
        BisSpeeches.Speech bowman = load.speeches().stream().filter(s -> s.author().contains("Bowman")).findFirst().orElseThrow();
        assertEquals(LocalDate.parse("2024-01-09"), bowman.bisDate());   // o campo date diz dezembro: vale a URL
        assertEquals(Instant.parse("2024-01-09T23:59:59Z"), bowman.availableUtc());
        assertTrue(bowman.text().contains("\"she said\""));      // aspas e quebra de linha dentro do campo
        BisSpeeches.Speech nagel = load.speeches().stream().filter(s -> s.author().contains("Nagel")).findFirst().orElseThrow();
        assertEquals("Deutsche Bundesbank", nagel.issuer().name());   // primeira instituição citada
        assertEquals("EUR", nagel.issuer().currency());

        try (LakeSql sql = LakeSql.open(lakeRoot.resolve("tmp"), "1GB")) {
            DocumentNormalizer.Report r = new DocumentNormalizer(lakeRoot).run(sql, 6000);
            // Waller: coletado ao vivo, fica o do coletor (horário real).
            // Nagel: o coletor só viu em 2026; o BIS tinha desde 15/02/2024 → fica o do BIS.
            assertEquals(2, r.bisDuplicates());
            assertEquals(3, r.docs());                           // Bowman e Nagel do BIS + Waller do coletor
            List<String> rows = sql.query("SELECT source || ' ' || issuer || ' ' || CAST(available_utc AS VARCHAR) "
                    + "|| ' ' || availability_estimated FROM read_parquet('" + LakeSql.slashes(r.output())
                    + "/**/*.parquet', hive_partitioning = true) ORDER BY available_utc", rs -> rs.getString(1));
            assertEquals(List.of("bis_speeches Federal Reserve 2024-01-09 23:59:59 true",
                    "bis_speeches Deutsche Bundesbank 2024-02-15 23:59:59 true",
                    "cb_web Federal Reserve 2024-03-04 19:00:00 false"), rows);
        }
    }

    @Test
    void semelhancaDeTitulos() {
        var bis = DocumentNormalizer.words(DocumentNormalizer.stripSpeaker("Christopher J Waller: Some thoughts on r-star"));
        assertTrue(DocumentNormalizer.similarity(bis, DocumentNormalizer.words("Waller, Some Thoughts on r-star")) >= 0.5);
        assertTrue(DocumentNormalizer.similarity(bis, DocumentNormalizer.words("Bowman, Opening Remarks")) < 0.5);
    }
}
