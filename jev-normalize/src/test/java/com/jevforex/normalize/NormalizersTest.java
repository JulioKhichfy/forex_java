package com.jevforex.normalize;

import com.jevforex.lake.LakeProperties;
import com.jevforex.lake.LakeSql;
import com.jevforex.lake.LocalDiskLakeStorage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NormalizersTest {

    @TempDir
    Path lakeRoot;
    private LocalDiskLakeStorage lake;
    private LakeSql sql;

    @BeforeEach
    void setUp() {
        lake = new LocalDiskLakeStorage(new LakeProperties(lakeRoot.toString()));
        sql = LakeSql.open(lakeRoot.resolve("tmp"), "1GB");
    }

    @AfterEach
    void tearDown() {
        sql.close();
    }

    /** Grava no bronze do mesmo jeito que o importador (csv + .meta.json com origin e seen_utc). */
    private void bronze(String source, String origin, String seenUtc, String body) {
        String meta = "#exporter=Test/1.0;server=S;login=1;offset_s=0;origin=" + origin + ";seen_utc=" + seenUtc + "\n";
        lake.writeBronze(source, Instant.parse(seenUtc), "csv", (meta + body).getBytes(StandardCharsets.UTF_8),
                Map.of("origin", origin, "seen_utc", seenUtc));
    }

    private static final String CANDLES = "market;symbol;broker_symbol;time_server;time_utc;open;high;low;close;"
            + "tick_volume;spread_points;real_volume\n";

    @Test
    void candles_deduplicaEParticiona() {
        bronze("mt5_candles", "HISTORY", "2026-10-09T13:00:00Z", CANDLES
                + "fx;EURUSD;EURUSDm;2026-10-09T12:58:00;2026-10-09T12:58:00Z;1.1;1.2;1.0;1.15;10;8;0\n"
                + "fx;EURUSD;EURUSDm;2026-10-09T12:59:00;2026-10-09T12:59:00Z;1.15;1.2;1.1;1.18;11;8;0\n");
        // a barra 12:59 aparece de novo num arquivo visto depois: fica a primeira
        bronze("mt5_candles", "LIVE", "2026-10-09T13:01:01Z", CANDLES
                + "fx;EURUSD;EURUSDm;2026-10-09T12:59:00;2026-10-09T12:59:00Z;9;9;9;9;99;9;0\n"
                + "fx;EURUSD;EURUSDm;2026-10-09T13:00:00;2026-10-09T13:00:00Z;1.18;1.19;1.17;1.17;12;9;0\n");

        CandleNormalizer.Report r = new CandleNormalizer(lakeRoot).run(sql, null);

        assertEquals(2, r.files());
        assertEquals(4, r.rowsRead());
        assertEquals(3, r.rowsWritten());
        assertEquals(0, r.rowsWithoutMeta());
        assertEquals(1, r.symbols().get(0).duplicatesDropped());
        assertTrue(Files.isDirectory(r.output().resolve("market=fx/symbol=EURUSD/year=2026/month=10")));

        String glob = "'" + LakeSql.slashes(r.output()) + "/**/*.parquet'";
        List<String> b = sql.query("SELECT origin || ' ' || close FROM read_parquet(" + glob
                + ", hive_partitioning = true) WHERE time_utc = TIMESTAMP '2026-10-09 12:59:00'", rs -> rs.getString(1));
        assertEquals(List.of("HISTORY 1.18"), b);
    }

    @Test
    void candles_buracoIntradiario() {
        bronze("mt5_candles", "HISTORY", "2026-10-09T13:00:00Z", CANDLES
                + "fx;USDJPY;USDJPYm;2026-10-08T10:00:00;2026-10-08T10:00:00Z;1;1;1;1;1;1;0\n"
                + "fx;USDJPY;USDJPYm;2026-10-08T10:45:00;2026-10-08T10:45:00Z;1;1;1;1;1;1;0\n");
        var s = new CandleNormalizer(lakeRoot).run(sql, null).symbols().get(0);
        assertEquals(1, s.intradayGaps());
        assertEquals(45, s.maxIntradayGap());
        assertEquals(LocalDateTime.parse("2026-10-08T10:00:00"), s.maxIntradayGapAfter());
    }

    @Test
    void candles_antesDoCorte_ficamForaDoSilver() {
        bronze("mt5_candles", "HISTORY", "2026-10-09T13:00:00Z", CANDLES
                + "fx;EURUSD;EURUSDm;2019-12-31T23:59:00;2019-12-31T23:59:00Z;1;1;1;1;1;1;0\n"
                + "fx;EURUSD;EURUSDm;2020-01-02T00:00:00;2020-01-02T00:00:00Z;1;1;1;1;1;1;0\n");
        var r = new CandleNormalizer(lakeRoot).run(sql, java.time.LocalDate.parse("2020-01-01"));
        assertEquals(1, r.rowsWritten());
        assertEquals(1, r.rowsBeforeReliable());
        assertEquals(LocalDateTime.parse("2020-01-02T00:00:00"), r.symbols().get(0).first());
    }

    @Test
    void aberturaSemanal_acompanhaNovaYork() {
        var r = WeeklyOpenCheck.evaluate(List.of(
                LocalDateTime.parse("2026-07-05T21:00:00"),    // verão americano: 17:00 NY = 21:00 UTC
                LocalDateTime.parse("2026-07-12T21:05:00"),
                LocalDateTime.parse("2026-01-04T22:00:00"),    // padrão: 17:00 NY = 22:00 UTC
                LocalDateTime.parse("2025-12-26T01:00:00")));  // reabertura depois do Natal (sexta)
        assertEquals(Map.of(21, 2L), r.summerHours());
        assertEquals(Map.of(22, 1L), r.winterHours());
        assertEquals(1, r.holidayReopens());
        assertTrue(r.verdict().startsWith("acompanha Nova York"));
    }

    @Test
    void aberturaSemanal_anoAntigoEmOutroFuso_apareceNaLista() {
        // o caso real da Exness: 2017 gravado em UTC+2/+3 (abre "à 0h"), 2024 certo
        var r = WeeklyOpenCheck.evaluate(List.of(
                LocalDateTime.parse("2017-07-09T00:00:00"), LocalDateTime.parse("2017-01-08T00:00:00"),
                LocalDateTime.parse("2024-07-07T21:05:00"), LocalDateTime.parse("2024-07-14T21:05:00"),
                LocalDateTime.parse("2024-01-07T22:05:00"), LocalDateTime.parse("2024-01-14T22:05:00")));
        assertEquals(java.util.Set.of(2017), r.yearsOutOfPattern().keySet());
        assertTrue(r.verdict().startsWith("ATENÇÃO: anos fora do padrão [2017]"));
    }

    @Test
    void aberturaSemanal_padraoEstranho_alerta() {
        var r = WeeklyOpenCheck.evaluate(List.of(
                LocalDateTime.parse("2026-07-05T20:00:00"), LocalDateTime.parse("2026-01-04T23:00:00")));
        assertTrue(r.verdict().startsWith("ATENÇÃO"));
    }

    private static final String CAL = "value_id;event_id;event_code;currency;country;importance;time_server;"
            + "time_utc;period;revision;actual_raw;forecast_raw;prev_raw;revised_prev_raw;impact\n";
    private static final String NONE = "-9223372036854775808";

    @Test
    void calendario_pointInTime() {
        // antes da divulgação: só forecast (retrato)
        bronze("mt5_calendar", "SNAPSHOT", "2026-10-14T09:00:00Z", CAL
                + "1;10;cpi-mm;USD;US;HIGH;2026-10-14T12:30:00;2026-10-14T12:30:00Z;2026-09-01;0;" + NONE
                + ";300000;200000;" + NONE + ";NA\n");
        // ao vivo: actual visto 6 s depois do horário
        bronze("mt5_calendar", "LIVE", "2026-10-14T12:30:06Z", CAL
                + "1;10;cpi-mm;USD;US;HIGH;2026-10-14T12:30:00;2026-10-14T12:30:00Z;2026-09-01;0;500000"
                + ";300000;200000;200000;POSITIVE\n");
        // o mesmo estado reaparece num retrato posterior: não duplica
        bronze("mt5_calendar", "SNAPSHOT", "2026-10-14T13:00:00Z", CAL
                + "1;10;cpi-mm;USD;US;HIGH;2026-10-14T12:30:00;2026-10-14T12:30:00Z;2026-09-01;0;500000"
                + ";300000;200000;200000;POSITIVE\n"
                // evento antigo exportado depois do fato: disponibilidade estimada
                + "2;20;nfp;USD;US;HIGH;2020-01-10T13:30:00;2020-01-10T13:30:00Z;;0;145000000;160000000;256000000;"
                + NONE + ";NEGATIVE\n");

        CalendarNormalizer.Report r = new CalendarNormalizer(lakeRoot).run(sql, 10);

        assertEquals(4, r.statesRead());
        assertEquals(3, r.statesWritten());
        assertEquals(2, r.valuesWithActual());
        assertEquals(1, r.liveLatency().samples());
        assertEquals(6.0, r.liveLatency().medianSeconds(), 1e-9);

        String glob = "'" + LakeSql.slashes(r.output()) + "/**/*.parquet'";
        record S(String origin, Double actual, String available, boolean estimated) {
        }
        List<S> states = sql.query("SELECT origin, actual, CAST(actual_available_utc AS VARCHAR), "
                + "availability_estimated FROM read_parquet(" + glob + ", hive_partitioning = true) "
                + "ORDER BY value_id, seen_utc", rs -> new S(rs.getString(1), (Double) rs.getObject(2),
                rs.getString(3), rs.getBoolean(4)));
        assertNull(states.get(0).actual());                       // forecast antes: sem actual
        assertNull(states.get(0).available());
        assertEquals("LIVE", states.get(1).origin());              // o mesmo estado visto depois não substitui
        assertEquals(0.5, states.get(1).actual(), 1e-12);
        assertEquals("2026-10-14 12:30:06", states.get(1).available());
        assertFalse(states.get(1).estimated());
        assertEquals(145.0, states.get(2).actual(), 1e-9);          // 145 mil (em milhares) ×10^6
        assertEquals("2020-01-10 13:30:10", states.get(2).available());   // scheduled + 10 s
        assertTrue(states.get(2).estimated());
    }

    @Test
    void semBronze_relatorioVazio() {
        assertEquals(0, new CandleNormalizer(lakeRoot).run(sql, null).rowsWritten());
        assertEquals(0, new CalendarNormalizer(lakeRoot).run(sql, 10).statesWritten());
    }
}
