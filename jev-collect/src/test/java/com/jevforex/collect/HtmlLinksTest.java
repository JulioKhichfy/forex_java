package com.jevforex.collect;

import org.junit.jupiter.api.Test;

import java.net.URI;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HtmlLinksTest {

    private static final URI PAGE = URI.create("https://www.federalreserve.gov/newsevents/pressreleases/monetary20260414a.htm");

    @Test
    void anexoDoFed_resolveLinkRelativo() {
        String html = """
                <nav><a href="/files/annual-report.pdf">Annual report</a></nav>
                <div id="article"><p>The Board released the minutes.</p>
                <a href="/monetarypolicy/files/minutes_discount_rate_20260414.pdf"><span>Attachment</span> (PDF)</a>
                </div><footer><a href="/privacy.pdf">Privacy</a></footer>""";
        var links = HtmlLinks.pdfLinks(html, PAGE, 3);
        assertEquals(1, links.size());   // menu e rodapé ficam de fora
        assertEquals("https://www.federalreserve.gov/monetarypolicy/files/minutes_discount_rate_20260414.pdf",
                links.get(0).url().toString());
        assertEquals("Attachment (PDF)", links.get(0).label());
    }

    @Test
    void outroSite_repetidoELimite() {
        String html = """
                <main>
                <a href="https://outro-site.com/x.pdf">fora</a>
                <a href='a.pdf'>A</a><a href="a.pdf">A de novo</a>
                <a href="b.PDF?v=2">B</a><a href="c.pdf">C</a>
                </main>""";
        var links = HtmlLinks.pdfLinks(html, PAGE, 2);
        assertEquals(2, links.size());
        assertTrue(links.get(0).url().toString().endsWith("/pressreleases/a.pdf"));
        assertTrue(links.get(1).url().toString().endsWith("/pressreleases/b.PDF?v=2"));
    }

    @Test
    void semMarcacaoDeArtigo_usaAPaginaToda() {
        assertEquals(1, HtmlLinks.pdfLinks("<p><a href=\"/x.pdf\">x</a></p>", PAGE, 3).size());
    }
}
