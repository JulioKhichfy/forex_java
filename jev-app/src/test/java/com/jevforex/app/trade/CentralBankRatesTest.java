package com.jevforex.app.trade;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class CentralBankRatesTest {

    @Test
    void enderecosDosComunicados() {
        // Fed 28/10 às 18:00 UTC = 14:00 em Nova York; BoC às 13:45 UTC = 09:45 em Toronto
        assertEquals("https://www.federalreserve.gov/newsevents/pressreleases/monetary20261028a.htm",
                CentralBankRates.url("USD", Instant.parse("2026-10-28T18:00:00Z")));
        assertEquals("https://www.bankofcanada.ca/2026/10/fad-press-release-2026-10-28/",
                CentralBankRates.url("CAD", Instant.parse("2026-10-28T13:45:00Z")));
        assertNull(CentralBankRates.url("JPY", Instant.parse("2026-10-30T03:00:00Z")));
    }

    @Test
    void taxaDoFed_tetoDaBanda() {
        assertEquals(4.0, CentralBankRates.parse("USD", "<p>The Committee decided to raise the target range for the federal "
                + "funds rate by 1/4 percentage point to 3-3/4 to 4 percent, in support of</p>").rate());
        assertEquals(4.0, CentralBankRates.parse("USD", "the Committee decided to maintain the target range for the federal "
                + "funds rate at 3-3/4 to 4 percent.").rate());
        assertEquals(3.75, CentralBankRates.parse("USD", "decided to lower the target range for the federal funds rate by "
                + "1/4 percentage point to 3-1/2 to 3-3/4 percent").rate());
        assertNull(CentralBankRates.parse("USD", "<html>Page not found</html>"));
    }

    @Test
    void taxaDoBoC() {
        CentralBankRates.Reading r = CentralBankRates.parse("CAD", "<p>The Bank of Canada today increased its target for the "
                + "overnight rate by 25&nbsp;basis points to 2.5%, with the Bank Rate at 2.75% and the deposit rate at 2.45%.</p>");
        assertEquals(2.5, r.rate());
        assertEquals(2.25, CentralBankRates.parse("CAD", "The Bank of Canada today held its target for the overnight rate at 2&frac14;%").rate());
        assertEquals(2.75, CentralBankRates.parse("CAD", "today reduced its target for the overnight rate to 2¾%").rate());
    }

    @Test
    void fracoes() {
        assertEquals(3.75, CentralBankRates.fraction("3-3/4"));
        assertEquals(4.0, CentralBankRates.fraction("4"));
    }
}
