package com.jevforex.collect.news;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NewsCollectorsTest {

    private static final NewsProperties.Gdelt CFG = new NewsProperties.Gdelt(true, null, 1, 0, 15, 15,
            List.of("cnbc.com", "apnews.com"), List.of("fxstreet.com"), List.of("ECON_", "SANCTIONS"),
            List.of("Bitcoin"));

    /** Uma linha do GKG 2.1 (27 colunas separadas por tab) com site, temas, tom e título. */
    private static String gkg(String domain, String themes, String title) {
        String[] c = new String[27];
        Arrays.fill(c, "");
        c[0] = "20261014130000-1";
        c[1] = "20261014130000";
        c[3] = domain;
        c[4] = "https://" + domain + "/x";
        c[7] = themes;
        c[11] = "jerome powell";
        c[13] = "federal reserve";
        c[15] = "0.48,2.1,1.6,3.7,20.1,0,412";
        c[26] = "<PAGE_LINKS></PAGE_LINKS><PAGE_TITLE>" + title + "</PAGE_TITLE>";
        return String.join("\t", c);
    }

    @Test
    void filtroDoGdelt_siteDaListaETemaOuPalavra() throws IOException {
        var f = GdeltCollector.Filter.of(CFG);
        String kept = f.apply(gkg("cnbc.com", "TAX_FNCACT;ECON_INFLATION;", "CPI inflation report &amp; \"core\""));
        var j = GdeltCollector.JSON.readTree(kept);
        assertEquals("CPI inflation report & \"core\"", j.path("title").asText());
        assertEquals("20261014130000", j.path("batch").asText());
        assertEquals(0.48, j.path("tone").asDouble(), 1e-12);
        assertEquals(3.7, j.path("polarity").asDouble(), 1e-12);
        assertNull(f.apply(gkg("cnbc.com", "TAX_FNCACT;SOC_POINTSOFINTEREST;", "Best pizza in town")), "sem tema");
        assertTrue(f.apply(gkg("cnbc.com", "", "Bitcoin jumps")) != null, "palavra no título (sem diferenciar maiúsculas)");
        assertTrue(f.apply(gkg("fxstreet.com", "", "EUR/USD Forecast")) != null, "site especializado: qualquer tema");
        assertNull(f.apply(gkg("blog.example.com", "ECON_INFLATION", "x")), "site fora da lista");
        assertNull(f.apply("linha\tquebrada"), "menos de 27 colunas");
    }

    @Test
    void entidadesHtmlNoTitulo() {
        assertEquals("Iran – a timeline 'x' & \"y\"",
                GdeltCollector.Filter.unescape("Iran &#x2013; a timeline &#39;x&#39; &amp; &quot;y&quot;"));
    }

    @Test
    void gzipDeterministico_mesmoConteudoMesmoHash() {
        assertArrayEquals(GdeltCollector.gzip("{\"batch\": \"1\"}\n"), GdeltCollector.gzip("{\"batch\": \"1\"}\n"));
    }

    @Test
    void listaDaSec_colunasParalelasViramDocumentos() throws IOException {
        var cols = GdeltCollector.JSON.readTree("""
                {"accessionNumber": ["0000320193-26-000018", "0000320193-26-000017"],
                 "form": ["8-K", "4"], "items": ["2.02,9.01", ""], "filingDate": ["2026-07-30", "2026-07-29"],
                 "acceptanceDateTime": ["2026-07-31T00:30:28.000Z", "2026-07-30T01:00:00.000Z"],
                 "primaryDocument": ["aapl-20260730.htm", "xslF345X05/wk-form4.xml"]}
                """);
        var f = EdgarCollector.parse("AAPL", 320193, cols);
        assertEquals(2, f.size());
        assertEquals("8-K", f.get(0).form());
        assertEquals("2.02,9.01", f.get(0).items());
        assertEquals("https://www.sec.gov/Archives/edgar/data/320193/000032019326000018/", f.get(0).folder());
    }
}
