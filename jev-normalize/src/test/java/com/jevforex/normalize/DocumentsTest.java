package com.jevforex.normalize;

import com.jevforex.lake.LakeProperties;
import com.jevforex.lake.LakeSql;
import com.jevforex.lake.LocalDiskLakeStorage;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DocumentsTest {

    @Test
    void chunker_agrupaParagrafosAteOLimite() {
        String text = "a".repeat(40) + "\n\n" + "b".repeat(40) + "\n\n" + "c".repeat(40);
        List<String> chunks = Chunker.split(text, 90);
        assertEquals(2, chunks.size());
        assertEquals("a".repeat(40) + "\n\n" + "b".repeat(40), chunks.get(0));
        assertEquals("c".repeat(40), chunks.get(1));
    }

    @Test
    void chunker_paragrafoGrande_cortaEmFrases() {
        String para = "Inflation remains elevated. ".repeat(10).trim();   // ~270 caracteres
        List<String> chunks = Chunker.split(para, 100);
        assertTrue(chunks.size() >= 3);
        chunks.forEach(c -> assertTrue(c.length() <= 100, c));
        chunks.forEach(c -> assertTrue(c.endsWith("."), c));        // corta entre frases
        assertTrue(Chunker.split("  ", 100).isEmpty());
    }

    @Test
    void html_soOCorpoDoArtigo() {
        String html = """
                <html><head><script>var x = 1;</script></head><body>
                <nav>Home | About</nav>
                <main><h1>Monetary policy decisions</h1>
                <p>The Governing Council decided to keep the three key ECB interest rates
                unchanged.</p><p>Inflation &amp; growth &#8211; outlook&nbsp;stable.</p></main>
                <footer>Copyright</footer></body></html>""";
        String t = DocumentText.extract(html.getBytes(StandardCharsets.UTF_8), "html");
        assertEquals("Monetary policy decisions\n\nThe Governing Council decided to keep the three key ECB "
                + "interest rates unchanged.\n\nInflation & growth – outlook stable.", t);
    }

    @Test
    void html_tiraRuidoDeInterfaceEBarraLateral() {
        // casos reais: aviso de navegador da RBNZ; botões duplicados e "Related Information" do BoC
        String article = "The Bank of Canada today held its target for the overnight rate at 2.25%. ".repeat(5);
        String html = "<main><p>Browser issue</p><p>It looks like the browser you're using doesn't work well with "
                + "our website.</p><h1>Rate announcement</h1><p>Share this page on X</p><p>Share this page on X</p>"
                + "<p>" + article + "</p><p>" + article + "</p><h2>Related Information</h2>"
                + "<p>Bank of Canada unveils new $20 bank note</p></main>";
        String t = DocumentText.extract(html.getBytes(StandardCharsets.UTF_8), "html");
        assertTrue(t.startsWith("Rate announcement\n\nThe Bank of Canada today held"), t);
        assertFalse(t.contains("Share this page"), t);
        assertFalse(t.contains("$20 bank note"), t);       // barra lateral ficou de fora
        assertEquals(2, t.split("\n\n").length);              // título + artigo (repetido em sequência sai)
    }

    @Test
    void html_relatedLogoNoComeco_naoCortaOArtigo() {
        String t = DocumentText.extract(("<main><p>Related information</p><p>" + "Inflation text. ".repeat(30)
                + "</p></main>").getBytes(StandardCharsets.UTF_8), "html");
        assertTrue(t.contains("Inflation text."), t);
    }

    @Test
    void pdf_extraiEJuntaHifenizacao() throws Exception {
        String t = DocumentText.extract(pdf("Minutes of the discount rate meetings. Persistent infla-",
                "tion pressures were noted by directors."), "pdf");
        assertTrue(t.contains("Persistent inflation pressures"), t);
        assertFalse(t.contains("\n"), t);   // as duas linhas são o mesmo parágrafo
    }

    @Test
    void normalizer_paginaEAnexoLigados(@TempDir Path lakeRoot) throws Exception {
        LocalDiskLakeStorage lake = new LocalDiskLakeStorage(new LakeProperties(lakeRoot.toString()));
        String pageUrl = "https://www.federalreserve.gov/newsevents/pressreleases/monetary20260414a.htm";
        Map<String, Object> pageMeta = meta(pageUrl, "Minutes of the Board's discount rate meetings", "html", null);
        var page = lake.writeBronze("cb_web", Instant.parse("2026-10-08T18:49:15Z"), "html",
                "<div id=\"article\"><p>The Board released the minutes.</p></div>".getBytes(StandardCharsets.UTF_8),
                pageMeta);
        Map<String, Object> pdfMeta = meta("https://www.federalreserve.gov/x/minutes.pdf",
                "Minutes — Attachment (PDF)", "pdf", pageUrl);
        lake.writeBronze("cb_web", Instant.parse("2026-10-09T15:00:00Z"), "pdf",
                pdf("Directors noted that inflation remained somewhat elevated."), pdfMeta);

        try (LakeSql sql = LakeSql.open(lakeRoot.resolve("tmp"), "1GB")) {
            DocumentNormalizer.Report r = new DocumentNormalizer(lakeRoot).run(sql, 6000);
            assertEquals(2, r.docs());
            assertEquals(2, r.chunks());
            assertTrue(r.failures().isEmpty(), r.failures().toString());
            assertEquals(1, r.issuers().get(0).attachments());

            String glob = "'" + LakeSql.slashes(r.output()) + "/**/*.parquet'";
            List<String> parents = sql.query("SELECT coalesce(parent_sha, '-') || ' ' || content_type || ' ' || text "
                    + "FROM read_parquet(" + glob + ", hive_partitioning = true) ORDER BY first_seen_at",
                    rs -> rs.getString(1));
            assertEquals("- html The Board released the minutes.", parents.get(0));
            assertEquals(page.sha256() + " pdf Directors noted that inflation remained somewhat elevated.",
                    parents.get(1));
        }
    }

    @Test
    void normalizer_anexoQueRepeteAPagina_entraUmaVez(@TempDir Path lakeRoot) throws Exception {
        LocalDiskLakeStorage lake = new LocalDiskLakeStorage(new LakeProperties(lakeRoot.toString()));
        String minutes = "Participants agreed that inflation remained elevated and that policy should stay restrictive. "
                .repeat(20);
        String page = "https://www.federalreserve.gov/monetarypolicy/fomcminutes20240131.htm";
        lake.writeBronze("cb_web", Instant.parse("2026-10-09T18:00:00Z"), "html",
                ("<div id=\"article\"><p>" + minutes + "</p></div>").getBytes(StandardCharsets.UTF_8),
                meta(page, "Minutes", "html", null));
        // a mesma ata em PDF (aqui, texto): fica de fora
        lake.writeBronze("cb_web", Instant.parse("2026-10-09T18:00:01Z"), "txt",
                minutes.getBytes(StandardCharsets.UTF_8), meta(page.replace(".htm", ".pdf"), "Minutes PDF", "txt", page));
        // anexo com conteúdo próprio (ex.: projeções): entra
        lake.writeBronze("cb_web", Instant.parse("2026-10-09T18:00:02Z"), "txt",
                "Summary of Economic Projections: median federal funds rate 4.6 percent.".getBytes(StandardCharsets.UTF_8),
                meta("https://www.federalreserve.gov/x/sep.pdf", "SEP", "txt", page));

        try (LakeSql sql = LakeSql.open(lakeRoot.resolve("tmp"), "1GB")) {
            DocumentNormalizer.Report r = new DocumentNormalizer(lakeRoot).run(sql, 6000);
            assertEquals(1, r.duplicateAttachments());
            assertEquals(2, r.docs());
        }
    }

    private static Map<String, Object> meta(String url, String title, String type, String parentUrl) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("feed_id", "fed-monetary");
        m.put("issuer", "Federal Reserve");
        m.put("currency", "USD");
        m.put("market", "fx");
        m.put("doc_type", "cb_text");
        m.put("url", url);
        m.put("title", title);
        m.put("published_at", "2026-04-14T18:00:00Z");
        m.put("content_type", type);
        if (parentUrl != null) m.put("parent_url", parentUrl);
        return m;
    }

    /** PDF de verdade, uma linha por argumento. */
    private static byte[] pdf(String... lines) throws Exception {
        try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PDPage page = new PDPage();
            doc.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.beginText();
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 11);
                cs.newLineAtOffset(72, 700);
                for (String line : lines) {
                    cs.showText(line);
                    cs.newLineAtOffset(0, -14);
                }
                cs.endText();
            }
            doc.save(out);
            return out.toByteArray();
        }
    }
}
