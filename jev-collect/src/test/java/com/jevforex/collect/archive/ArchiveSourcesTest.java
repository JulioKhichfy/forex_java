package com.jevforex.collect.archive;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ArchiveSourcesTest {

    @Test
    void fed_comunicadosEAtasDaPaginaDoCalendario() {
        String html = """
                <a href="/newsevents/pressreleases/monetary20200129a.htm">Statement</a>
                <a href="/newsevents/pressreleases/monetary20240131a.htm">Statement</a>
                <a href="/newsevents/pressreleases/monetary20240131a.htm">HTML</a>
                <a href="/monetarypolicy/fomcminutes20240131.htm">Minutes</a>""";
        var items = ArchiveSources.parseFed(html, 2021);
        assertEquals(2, items.size());                                   // 2020 fica de fora; repetido entra 1 vez
        assertEquals("fomc-meeting-statement", items.get(0).releaseEvent());
        assertEquals("same_day", items.get(0).releaseAnchor());
        assertEquals("https://www.federalreserve.gov/monetarypolicy/fomcminutes20240131.htm", items.get(1).url());
        assertEquals("first_after", items.get(1).releaseAnchor());       // a ata sai 3 semanas depois
        assertEquals(LocalDate.parse("2024-01-31"), items.get(1).refDate());
    }

    @Test
    void bce_indicesAnuais() {
        String html = "<a href=\"/press/pr/date/2024/html/ecb.mp240125~f738889bde.en.html\">x</a>"
                + "<a href=\"/press/accounts/2024/html/ecb.mg240222~1d9e3c1b2a.en.html\">y</a>";
        var mp = ArchiveSources.parseEcb(html, "mp");
        assertEquals(1, mp.size());
        assertEquals("https://www.ecb.europa.eu/press/pr/date/2024/html/ecb.mp240125~f738889bde.en.html", mp.get(0).url());
        assertEquals("ecb-interest-rate-decision", mp.get(0).releaseEvent());
        var mg = ArchiveSources.parseEcb(html, "mg");
        assertEquals("accounts", mg.get(0).kind());
        assertEquals(LocalDate.parse("2024-02-22"), mg.get(0).refDate());
    }

    @Test
    void boeEBoc_urlPelaDataDaDecisao() {
        assertEquals("https://www.bankofengland.co.uk/monetary-policy-summary-and-minutes/2024/august-2024",
                ArchiveSources.boeItem(LocalDate.parse("2024-08-01")).url());
        assertEquals("https://www.bankofcanada.ca/2024/01/fad-press-release-2024-01-24/",
                ArchiveSources.bocItem(LocalDate.parse("2024-01-24")).url());
    }

    @Test
    void boj_comunicadosEAtas() {
        String st = "<a href=\"/en/mopo/mpmdeci/state_2024/k240123a.htm\">x</a>"
                + "<a href=\"/en/mopo/mpmdeci/state_2023/index.htm\">2023</a>";
        assertEquals(1, ArchiveSources.parseBojStatements(st, 2024).size());
        var min = ArchiveSources.parseBojMinutes("<a href=\"/en/mopo/mpmsche_minu/minu_2024/g240123.pdf\">", 2024);
        assertEquals("after_next:boj-monetary-policy-statement", min.get(0).releaseAnchor());
        // índices de anos anteriores linkam a página .htm (o PDF vem como anexo)
        var old = ArchiveSources.parseBojMinutes("<a href=\"/en/mopo/mpmsche_minu/minu_2023/g231031.htm\">"
                + "<a href=\"/en/mopo/mpmsche_minu/minu_2023/g231031.pdf\">", 2023);
        assertEquals(1, old.size());
        assertEquals("https://www.boj.or.jp/en/mopo/mpmsche_minu/minu_2023/g231031.htm", old.get(0).url());
    }
}
