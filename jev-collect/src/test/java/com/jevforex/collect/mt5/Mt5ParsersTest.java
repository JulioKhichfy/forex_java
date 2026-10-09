package com.jevforex.collect.mt5;

import com.jevforex.core.BrokerSymbols;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Mt5ParsersTest {

    private static final BrokerSymbols EXNESS = new BrokerSymbols("m");

    private static Mt5Csv csv(String text) {
        return Mt5Csv.parse(text.getBytes(StandardCharsets.UTF_8));
    }

    private static String meta(int offset, String origin) {
        return "#exporter=Test/1.0;server=Exness-MT5Trial11;login=123;offset_s=" + offset
                + ";origin=" + origin + ";seen_utc=2026-10-14T12:30:06Z\n";
    }

    private static final String CAL_HEADER = "value_id;event_id;event_code;currency;country;importance;time_server;"
            + "time_utc;period;revision;actual_raw;forecast_raw;prev_raw;revised_prev_raw;impact\n";

    @Test
    void calendario_cpiDoExemplo_decodificaEscalaEAusencia() {
        // CPI m/m do capítulo 14: actual 0,5, forecast 0,3; revised_prev ausente (LONG_MIN)
        var c = csv(meta(0, "LIVE") + CAL_HEADER
                + "19882211;840030016;cpi-mm;USD;US;HIGH;2026-10-14T12:30:00;2026-10-14T12:30:00Z;2026-09-01;0;"
                + "500000;300000;200000;-9223372036854775808;POSITIVE\n");
        var v = Mt5Parsers.calendar(c, Mt5Parsers.FileMeta.of(c)).get(0);
        assertEquals(new BigDecimal("0.5"), v.actual());
        assertEquals(new BigDecimal("0.3"), v.forecast());
        assertEquals(new BigDecimal("0.2"), v.previous());
        assertNull(v.revisedPrevious());
        assertEquals(Instant.parse("2026-10-14T12:30:00Z"), v.scheduledAt());
        assertEquals(Instant.parse("2026-10-14T12:30:06Z"), v.firstSeenAt());
        assertEquals("LIVE", v.origin());
    }

    @Test
    void calendario_estadoMudaQuandoSaiOActual() {
        String antes = "1;2;cpi-mm;USD;US;HIGH;2026-10-14T12:30:00;2026-10-14T12:30:00Z;2026-09-01;0;"
                + "-9223372036854775808;300000;200000;-9223372036854775808;NA\n";
        String depois = antes.replace("0;-9223372036854775808;300000", "0;500000;300000");
        var a = csv(meta(0, "LIVE") + CAL_HEADER + antes);
        var b = csv(meta(0, "LIVE") + CAL_HEADER + depois);
        var va = Mt5Parsers.calendar(a, Mt5Parsers.FileMeta.of(a)).get(0);
        var vb = Mt5Parsers.calendar(b, Mt5Parsers.FileMeta.of(b)).get(0);
        assertNull(va.actual());
        assertNotEquals(va.stateSha(), vb.stateSha());
    }

    @Test
    void calendario_servidorGmtMais3_converteParaUtc() {
        // corretora em GMT+3: 15:30 no servidor = 12:30 UTC
        var c = csv(meta(10800, "SNAPSHOT") + CAL_HEADER
                + "1;2;cpi-mm;USD;US;HIGH;2026-10-14T15:30:00;2026-10-14T12:30:00Z;;;"
                + "1;1;1;1;\n");
        var v = Mt5Parsers.calendar(c, Mt5Parsers.FileMeta.of(c)).get(0);
        assertEquals(Instant.parse("2026-10-14T12:30:00Z"), v.scheduledAt());
        assertNull(v.period());
        assertNull(v.revision());
    }

    @Test
    void calendario_utcInconsistenteComOffset_recusa() {
        var c = csv(meta(0, "LIVE") + CAL_HEADER
                + "1;2;cpi-mm;USD;US;HIGH;2026-10-14T15:30:00;2026-10-14T12:30:00Z;;;1;1;1;1;\n");
        assertThrows(IllegalArgumentException.class, () -> Mt5Parsers.calendar(c, Mt5Parsers.FileMeta.of(c)));
    }

    @Test
    void decodeScaled_inteirosENegativos() {
        assertEquals(new BigDecimal("254"), Mt5Parsers.decodeScaled("254000000"));
        assertEquals(new BigDecimal("-0.1"), Mt5Parsers.decodeScaled("-100000"));
        assertNull(Mt5Parsers.decodeScaled(""));
    }

    private static final String CANDLE_HEADER = "market;symbol;broker_symbol;time_server;time_utc;open;high;low;close;"
            + "tick_volume;spread_points;real_volume\n";

    @Test
    void candles_resumoPorSimbolo() {
        var c = csv(meta(0, "LIVE") + CANDLE_HEADER
                + "fx;EURUSD;EURUSDm;2026-10-14T12:31:00;2026-10-14T12:31:00Z;1.08330;1.08340;1.08200;1.08220;812;9;0\n"
                + "fx;EURUSD;EURUSDm;2026-10-14T12:32:00;2026-10-14T12:32:00Z;1.08220;1.08230;1.08190;1.08210;301;9;0\n"
                + "fx;USDJPY;USDJPYm;2026-10-14T12:32:00;2026-10-14T12:32:00Z;149.000;149.060;148.990;149.050;400;12;0\n");
        var candles = Mt5Parsers.candles(c, Mt5Parsers.FileMeta.of(c), EXNESS);
        var s = Mt5Parsers.summarize(candles);
        assertEquals(2, s.size());
        assertEquals("EURUSD", s.get(0).symbol());
        assertEquals(2, s.get(0).bars());
        assertEquals(Instant.parse("2026-10-14T12:32:00Z"), s.get(0).lastBar());
        assertEquals(1.08210, s.get(0).lastClose(), 1e-9);
    }

    @Test
    void candles_semSufixoDaCorretora_recusa() {
        // EURUSD sem "m": pode vir de outro terminal que escreve na mesma Common\Files
        var c = csv(meta(0, "LIVE") + CANDLE_HEADER
                + "fx;EURUSD;EURUSD;2026-10-14T12:31:00;2026-10-14T12:31:00Z;1;1;1;1;1;1;0\n");
        assertThrows(IllegalArgumentException.class,
                () -> Mt5Parsers.candles(c, Mt5Parsers.FileMeta.of(c), EXNESS));
    }

    @Test
    void meta_incompleta_recusa() {
        var c = csv("#exporter=Test/1.0;server=X\n" + CANDLE_HEADER);
        var e = assertThrows(IllegalArgumentException.class, () -> Mt5Parsers.FileMeta.of(c));
        assertTrue(e.getMessage().contains("login"));
    }

    @Test
    void linhaComCamposAMais_recusaComNumeroDaLinha() {
        var e = assertThrows(IllegalArgumentException.class,
                () -> csv(meta(0, "LIVE") + "a;b\n1;2\n1;2;3\n"));
        assertTrue(e.getMessage().contains("linha 4"));
    }

    @Test
    void tipoPeloNome() {
        assertEquals(Mt5FileKind.CANDLES,
                Mt5FileKind.fromFileName("candles_20261008T190100Z_live-000042.csv").orElseThrow());
        assertEquals(Mt5FileKind.CALEVENTS,
                Mt5FileKind.fromFileName("calevents_20261008T190000Z_all.csv").orElseThrow());
        assertTrue(Mt5FileKind.fromFileName("candles_x.csv.tmp").isEmpty());
        assertTrue(Mt5FileKind.fromFileName("outro_20261008.csv").isEmpty());
    }

    @Test
    void contaOuServidorDiferente_recusa() {
        var p = new Mt5Properties(null, true, 2, "m", 12345678L, "Exness-MT5Trial11", 30, 150);
        assertNull(p.rejectAccount(12345678L, "Exness-MT5Trial11"));
        assertTrue(p.rejectAccount(111L, "Exness-MT5Trial11").contains("conta"));
        assertTrue(p.rejectAccount(12345678L, "ICMarkets-Demo").contains("servidor"));
        var semConferencia = new Mt5Properties(null, true, 2, "m", 0, "", 30, 150);
        assertNull(semConferencia.rejectAccount(111L, "Qualquer"));
    }
}
