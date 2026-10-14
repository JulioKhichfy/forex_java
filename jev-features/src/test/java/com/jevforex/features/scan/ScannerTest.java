package com.jevforex.features.scan;

import com.jevforex.lake.LakeSql;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScannerTest {

    private static final LocalDateTime T0 = LocalDateTime.of(2026, 9, 1, 0, 0);

    /** Barras de um período com fechamentos dados; open = fechamento anterior, high/low = ±0,0002. */
    private static List<Bar> bars(Tf tf, double[] closes, long volume) {
        List<Bar> out = new ArrayList<>();
        for (int i = 0; i < closes.length; i++) {
            double o = i == 0 ? closes[0] : closes[i - 1];
            out.add(new Bar(T0.plusMinutes((long) tf.minutes * i), o, Math.max(o, closes[i]) + 0.0002,
                    Math.min(o, closes[i]) - 0.0002, closes[i], volume, true));
        }
        return out;
    }

    private static double[] flat(int n, double px) {
        double[] c = new double[n];
        for (int i = 0; i < n; i++) c[i] = px + (i % 2 == 0 ? 0.0001 : -0.0001);
        return c;
    }

    @Test
    void rsi_serieSoSubindo_eh100_eAlternada_eh50() {
        double[] up = new double[30];
        for (int i = 0; i < up.length; i++) up[i] = 1 + i * 0.001;
        assertEquals(100, Indicators.rsi(up, 14)[29], 1e-9);
        assertTrue(Double.isNaN(Indicators.rsi(up, 14)[13]), "sem 14 variações ainda não há RSI");
        double[] alt = new double[31];
        for (int i = 0; i < alt.length; i++) alt[i] = i % 2 == 0 ? 1.0 : 1.01;
        assertEquals(50, Indicators.rsi(alt, 14)[30], 2.0);
    }

    @Test
    void bollinger_desvioPopulacional_eMediaExponencial() {
        double[] x = {1, 2, 3, 4, 5};
        assertEquals(3, Indicators.sma(x, 5)[4], 1e-12);
        assertEquals(Math.sqrt(2), Indicators.std(x, 5)[4], 1e-12);
        assertEquals(3, Indicators.ema(new double[]{3, 3, 3, 3}, 3)[3], 1e-12);
    }

    @Test
    void barraGrandeComVolume_geraBollingerCorpoEVolume_eUsaSoBarrasFechadas() {
        double[] c = flat(60, 1.1000);
        c[59] = 1.1050;   // salto de ~25 ATR na última barra fechada
        List<Bar> h1 = new ArrayList<>(bars(Tf.H1, c, 100));
        Bar big = h1.remove(h1.size() - 1);
        h1.add(new Bar(big.t(), big.open(), big.high(), big.low(), big.close(), 900, true));
        // barra em formação depois dela: não pode virar alerta (o indicador usa só as fechadas)
        h1.add(new Bar(big.t().plusHours(1), 1.1050, 1.1200, 1.1050, 1.1200, 5, false));
        Map<Tf, List<Bar>> m = new EnumMap<>(Tf.class);
        m.put(Tf.H1, h1);
        Scanner.Result r = Scanner.scan(new Scanner.Input("EURUSD", List.of("EUR", "USD"), m, List.of(), null, null));

        Scanner.TfState s = r.tfs().stream().filter(x -> x.tf().equals("H1")).findFirst().orElseThrow();
        assertEquals(1.1050, s.close(), 1e-12, "estado na última barra FECHADA");
        assertTrue(s.pctB() > 1);
        assertEquals(9.0, s.volRatio(), 1e-9, "900 ÷ mediana 100 das barras anteriores");
        assertTrue(r.alerts().stream().anyMatch(a -> a.kind().equals("BB") && a.dir().equals("up")));
        assertTrue(r.alerts().stream().anyMatch(a -> a.kind().equals("CANDLE") && a.severity() == 3));
        assertTrue(r.alerts().stream().anyMatch(a -> a.kind().equals("VOLUME") && a.severity() == 3));
        assertTrue(r.alerts().stream().anyMatch(a -> a.kind().equals("RSI") && a.dir().equals("up")));
        assertEquals(3, r.alerts().get(0).severity(), "os mais fortes primeiro");
    }

    @Test
    void volumeDoMesmoHorario_naoAcusaASazonalidade() {
        // 25 dias de H1: volume 100, mas 1000 todo dia às 08:00 (abertura de Londres). A barra das 08:00 de hoje com
        // 1000 é normal PARA O HORÁRIO; comparada com as 20 barras anteriores pareceria 10×.
        List<Bar> h1 = new ArrayList<>();
        int n = 25 * 24 + 9;
        for (int i = 0; i < n; i++) {
            LocalDateTime t = T0.plusHours(i);
            h1.add(new Bar(t, 1.1, 1.1002, 1.0998, 1.1, t.getHour() == 8 ? 1000 : 100, true));
        }
        assertEquals(8, h1.get(h1.size() - 1).t().getHour());
        assertEquals(1.0, Scanner.volumeRatio(Tf.H1, h1), 1e-9);
        assertEquals(10.0, Scanner.volumeRatio(Tf.H4, h1), 1e-9, "H4 compara com as 20 barras anteriores");
    }

    @Test
    void movimentoEmAtr_eGap() {
        // M1: 26 h estáveis com um buraco de 2 h e reabertura 3 ATR(H1) acima
        List<Bar> m1 = new ArrayList<>();
        LocalDateTime t = T0;
        double px = 1.1;
        for (int i = 0; i < 26 * 60; i++, t = t.plusMinutes(1)) {
            if (i >= 600 && i < 720) continue;   // sem barras das 10:00 às 12:00
            if (i == 720) px = 1.1030;
            m1.add(new Bar(t, px, px, px, px, 10, true));
        }
        Map<Tf, List<Bar>> m = new EnumMap<>(Tf.class);
        m.put(Tf.H1, bars(Tf.H1, flat(40, 1.1), 50));   // ATR(H1) ≈ 0,0006
        Scanner.Result r = Scanner.scan(new Scanner.Input("EURUSD", List.of("EUR", "USD"), m, m1, 12, 4.0));
        assertTrue(r.alerts().stream().anyMatch(a -> a.kind().equals("GAP") && a.dir().equals("up")));
        assertTrue(r.alerts().stream().anyMatch(a -> a.kind().equals("SPREAD") && a.severity() == 2), "12 = 3× o típico");
        Scanner.Move h24 = r.moves().stream().filter(x -> x.window().equals("24h")).findFirst().orElseThrow();
        assertNotNull(h24.pct());
        assertNull(h24.atrs(), "sem D1 não há ATR diário");
        assertEquals(1.1030, r.price(), 1e-12);
    }

    @Test
    void forcaDasMoedas_somaZero_eOrdem() {
        Scanner.Result eur = result("EURUSD", 0.5), jpy = result("USDJPY", 0.5);
        Map<String, Double> s = Scanner.currencyStrength(List.of(eur, jpy), List.of("1h")).get("1h");
        // EUR +0,5 contra o USD; JPY −0,5 (USDJPY subiu); USD 0 → média 0
        assertEquals(0.5, s.get("EUR"), 1e-12);
        assertEquals(0.0, s.get("USD"), 1e-12);
        assertEquals(-0.5, s.get("JPY"), 1e-12);
        assertEquals(List.of("EUR", "USD", "JPY"), List.copyOf(s.keySet()));
    }

    private static Scanner.Result result(String symbol, double pct) {
        return new Scanner.Result(symbol, List.of(), null, 1.0, null, null, List.of(new Scanner.Move("1h", pct, null)),
                List.of(), null, List.of());
    }

    @Test
    void loader_domingoEntraNaSegunda_barraEmFormacao_eMoedas(@TempDir Path lake) {
        lake.resolve("silver").toFile().mkdirs();
        try (LakeSql sql = LakeSql.open(lake.resolve("tmp"), "1GB")) {
            // EURUSD de sexta 25/09 a terça 29/09/2026 10:29 (domingo a partir das 21:00), preço constante
            sql.execute("""
                    COPY (
                      SELECT 'fx' AS market, 'EURUSD' AS symbol, 'EURUSDm' AS broker_symbol, g.ts AS time_utc,
                             1.1 AS open, 1.1002 AS high, 1.0998 AS low, 1.1 AS close, 10::BIGINT AS tick_volume,
                             8 AS spread_points, 0::BIGINT AS real_volume, 'HISTORY' AS origin, g.ts AS seen_utc,
                             year(g.ts) AS year, month(g.ts) AS month
                        FROM generate_series(TIMESTAMP '2026-09-25 00:00', TIMESTAMP '2026-09-29 10:29',
                                             INTERVAL 1 MINUTE) g(ts)
                       WHERE isodow(g.ts) BETWEEN 1 AND 5 OR (isodow(g.ts) = 7 AND hour(g.ts) >= 21))
                    TO %s (FORMAT PARQUET, PARTITION_BY (market, symbol, year, month))
                    """.formatted(LakeSql.literal(lake.resolve("silver/candles_m1"))));
            ScanLoader loader = new ScanLoader(sql, lake);
            ScanLoader.Loaded l = loader.load("fx", null, LocalDateTime.of(2026, 9, 29, 10, 30), 10);
            Scanner.Input in = l.inputs().get("EURUSD");
            assertEquals(List.of("EUR", "USD"), in.currencies());
            List<Bar> d1 = in.bars().get(Tf.D1);
            assertEquals(List.of(LocalDateTime.of(2026, 9, 25, 0, 0), LocalDateTime.of(2026, 9, 28, 0, 0),
                    LocalDateTime.of(2026, 9, 29, 0, 0)), d1.stream().map(Bar::t).toList(), "domingo dentro da segunda");
            assertEquals(1440 * 10 + 180 * 10, d1.get(1).volume(), "segunda = domingo 21h-24h + segunda inteira");
            assertFalse(d1.get(2).closed(), "o dia de hoje está em formação");
            List<Bar> h1 = in.bars().get(Tf.H1);
            assertFalse(h1.get(h1.size() - 1).closed(), "a barra das 10:00 só fecha às 11:00");
            assertTrue(h1.get(h1.size() - 2).closed());

            // bronze recente: barras depois do silver entram; arquivo já lido não é relido; sem .meta.json espera
            bronze(lake, "a".repeat(64), "2026-09-29T10:30:00Z", 1.2, true);
            l = loader.load("fx", null, LocalDateTime.of(2026, 9, 29, 10, 32), 10);
            assertEquals(1, l.newFiles());
            List<Bar> m1 = l.inputs().get("EURUSD").m1();
            assertEquals(1.2, m1.get(m1.size() - 1).close(), 1e-12, "a barra do bronze é a última");
            bronze(lake, "b".repeat(64), "2026-09-29T10:31:00Z", 1.3, false);
            l = loader.load("fx", null, LocalDateTime.of(2026, 9, 29, 10, 32), 10);
            assertEquals(0, l.newFiles(), "o arquivo sem meta ainda está sendo gravado");
            assertEquals(1, l.recentFiles());
        }
    }

    /** Um arquivo do bronze de candles (o formato do importador): CSV com #meta e o .meta.json ao lado. */
    private static void bronze(Path lake, String sha, String timeUtc, double px, boolean withMeta) {
        try {
            Path dir = lake.resolve("bronze/mt5_candles/date=2026-09-29");
            java.nio.file.Files.createDirectories(dir);
            java.nio.file.Files.writeString(dir.resolve(sha + ".csv"), "#exporter=Test/1.0;origin=LIVE\n"
                    + "market;symbol;broker_symbol;time_server;time_utc;open;high;low;close;tick_volume;spread_points;real_volume\n"
                    + "fx;EURUSD;EURUSDm;" + timeUtc.replace("Z", "") + ";" + timeUtc + ";" + px + ";" + px + ";" + px + ";"
                    + px + ";10;8;0\n");
            if (withMeta) {
                java.nio.file.Files.writeString(dir.resolve(sha + ".meta.json"), "{\"sha256\": \"" + sha
                        + "\", \"origin\": \"live\", \"seen_utc\": \"" + timeUtc + "\"}");
            }
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }
}
