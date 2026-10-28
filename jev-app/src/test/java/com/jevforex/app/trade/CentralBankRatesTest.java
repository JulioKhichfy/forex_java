package com.jevforex.app.trade;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class CentralBankRatesTest {

    private static CentralBankRates.Reading read(String cur, String html) {
        return CentralBankRates.parse(cur, CentralBankRates.text(html));
    }

    @Test
    void enderecosDosComunicados() {
        // Fed 28/10 às 18:00 UTC = 14:00 em Nova York; BoC às 13:45 UTC = 09:45 em Toronto
        assertEquals("https://www.federalreserve.gov/newsevents/pressreleases/monetary20261028a.htm",
                CentralBankRates.url("USD", Instant.parse("2026-10-28T18:00:00Z")));
        assertEquals("https://www.bankofcanada.ca/2026/10/fad-press-release-2026-10-28/",
                CentralBankRates.url("CAD", Instant.parse("2026-10-28T13:45:00Z")));
        // BoJ: 30/10 às 02:30 UTC = 11:30 em Tóquio
        assertEquals("https://www.boj.or.jp/en/mopo/mpmdeci/mpr_2026/k261030a.pdf",
                CentralBankRates.url("JPY", Instant.parse("2026-10-30T02:30:00Z")));
        assertNull(CentralBankRates.url("EUR", Instant.parse("2026-10-29T13:15:00Z")), "BCE: pela lista");
        assertNull(CentralBankRates.url("GBP", Instant.parse("2026-11-05T12:00:00Z")));
    }

    @Test
    void linkDoBce_naListaOuNoRss() {
        Instant at = Instant.parse("2026-09-10T12:15:00Z");
        String expected = "https://www.ecb.europa.eu/press/pr/date/2026/html/ecb.mp260910~314e508016.en.html";
        assertEquals(expected, CentralBankRates.ecbLink("<a href=\"/press/pr/date/2026/html/ecb.mp260910~314e508016.en.html\">", at));
        assertEquals(expected, CentralBankRates.ecbLink(
                "<link>https://www.ecb.europa.eu//press/pr/date/2026/html/ecb.mp260910~314e508016.en.html</link>", at));
        assertNull(CentralBankRates.ecbLink("<a href=\"/press/pr/date/2026/html/ecb.mp260723~aa.en.html\">", at), "outro dia");
        assertNull(CentralBankRates.ecbLink("<a href=\"/press/pr/date/2026/html/ecb.mp260910~314e508016.de.html\">", at), "outra língua");
    }

    @Test
    void taxaDoFed_tetoDaBanda() {
        assertEquals(4.0, read("USD", "<p>The Committee decided to raise the target range for the federal "
                + "funds rate by 1/4 percentage point to 3-3/4 to 4 percent, in support of</p>").rate());
        assertEquals(4.0, read("USD", "the Committee decided to maintain the target range for the federal "
                + "funds rate at 3-3/4 to 4 percent.").rate());
        assertEquals(3.75, read("USD", "decided to lower the target range for the federal funds rate by "
                + "1/4 percentage point to 3-1/2 to 3-3/4 percent").rate());
        assertNull(read("USD", "<html>Page not found</html>"));
    }

    @Test
    void taxaDoBoC() {
        assertEquals(2.5, read("CAD", "<p>The Bank of Canada today increased its target for the "
                + "overnight rate by 25&nbsp;basis points to 2.5%, with the Bank Rate at 2.75% and the deposit rate at 2.45%.</p>").rate());
        assertEquals(2.25, read("CAD", "The Bank of Canada today held its target for the overnight rate at 2&frac14;%").rate());
        assertEquals(2.75, read("CAD", "today reduced its target for the overnight rate to 2¾%").rate());
    }

    @Test
    void taxaDoBce_refinanciamento() {
        assertEquals(2.65, read("EUR", "The Governing Council decided to raise the three key ECB interest rates by 25 basis "
                + "points. Accordingly, the interest rates on the deposit facility, the main refinancing operations and the "
                + "marginal lending facility will be increased to 2.50%, 2.65% and 2.90% respectively, with effect from").rate());
        assertEquals(2.15, read("EUR", "The interest rates on the deposit facility, the main refinancing operations and the "
                + "marginal lending facility will remain unchanged at 2.00%, 2.15% and 2.40% respectively.").rate());
    }

    @Test
    void taxaDoBoJ_aDecisaoNaoOVotoContrario() {
        String t = "decided, by an 8-1 majority vote, to set the following guideline: [Note] The Bank will encourage the "
                + "uncollateralized overnight call rate to remain at around 1.0 percent. [Note] He proposed that the Bank "
                + "would encourage the uncollateralized overnight call rate to remain at around 1.25 percent.";
        assertEquals(1.0, CentralBankRates.parse("JPY", t).rate());
    }

    @Test
    void intervaloEntreTentativas() {
        Instant at = Instant.parse("2026-10-30T02:30:00Z");
        assertEquals(Duration.ofSeconds(15), CentralBankRates.interval("JPY", at, at.plus(Duration.ofMinutes(90))), "BoJ sem hora exata");
        assertEquals(Duration.ofSeconds(5), CentralBankRates.interval("USD", at, at.plusSeconds(60)));
        assertEquals(Duration.ofMinutes(1), CentralBankRates.interval("USD", at, at.plus(Duration.ofMinutes(10))));
    }

    @Test
    void fracoes() {
        assertEquals(3.75, CentralBankRates.fraction("3-3/4"));
        assertEquals(4.0, CentralBankRates.fraction("4"));
    }
}
