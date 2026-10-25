package com.jevforex.app.trade;

import com.jevforex.features.scan.Bar;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CasinoServiceTest {

    private static CasinoService.Line line(int times, int attempts, int cycle, int used) {
        return new CasinoService.Line("XAUUSD", "metals", "BUY", false, null, 0.10, 1.0, attempts, times, null, true,
                cycle, used, 0, 0, "RUNNING", null, Instant.now(), null);
    }

    @Test
    void lucroMinimo_menorAlvoQuePassaDaTrava() {
        // EURUSD: ponto 0,00001, spread 12 pontos, sem mínimo da corretora → 2 spreads (24) + 1 spread de margem (12) + 1
        assertEquals(0.00037, OrderService.minTakeDistance(0.00001, 0, 12, 1.0), 1e-12);
        // sem margem: só passa da trava (2 spreads + 1 ponto)
        assertEquals(0.00025, OrderService.minTakeDistance(0.00001, null, 12, 0), 1e-12);
        // mínimo da corretora maior que 2 spreads manda
        assertEquals(0.00051, OrderService.minTakeDistance(0.00001, 50, 12, 0), 1e-12);
        // a trava do envio exige alvo > max(2 spreads, mínimo): sempre passa
        double d = OrderService.minTakeDistance(0.01, 10, 30, 0);
        assertTrue(d > Math.max(10 * 0.01, 2 * 30 * 0.01));
        // sem spread (sem cotação): não calcula — falhar fechado
        assertNull(OrderService.minTakeDistance(0.00001, 0, null, 1));
    }

    private static CasinoService.Entry entry(long id, int attempt, String status, Double result) {
        return new CasinoService.Entry(id, attempt, status, result, "motivo");
    }

    @Test
    void umaVez_tentativasAteOAlvo() {
        assertEquals(CasinoService.Action.SUBMIT, CasinoService.decide(line(1, 3, 1, 0), null).action(), "ligou: abre na hora");
        assertEquals(CasinoService.Action.WAIT, CasinoService.decide(line(1, 3, 1, 1), entry(7, 1, "SENT", null)).action());
        CasinoService.Decision again = CasinoService.decide(line(1, 3, 1, 1), entry(7, 1, "CLOSED", -1.0));
        assertEquals(CasinoService.Action.SUBMIT, again.action(), "fechou no stop com tentativas sobrando: tenta de novo");
        assertEquals(1, again.line().cycle());
        assertEquals(7, again.line().counted());
        CasinoService.Decision hit = CasinoService.decide(line(1, 3, 1, 2), entry(8, 2, "TARGET", 0.10));
        assertEquals(CasinoService.Action.FINISH, hit.action());
        assertEquals("DONE", hit.line().status(), "bateu o alvo na única vez: a linha termina");
        assertEquals(1, hit.line().hits());
        assertEquals("DONE", CasinoService.decide(line(1, 3, 1, 3), entry(9, 3, "CLOSED", -1.0)).line().status(),
                "acabaram as tentativas");
        assertEquals("STOPPED", CasinoService.decide(line(1, 3, 1, 1), entry(9, 1, "FAILED", null)).line().status(), "falha: falhar fechado");
        assertEquals("STOPPED", CasinoService.decide(line(1, 3, 1, 1), entry(9, 1, "REFUSED", null)).line().status());
    }

    @Test
    void vezes_abreDeNovoAconteçaOQueAcontecer() {
        // 3 vezes × 2 tentativas: bateu o alvo na vez 1 → vez 2, tentativas zeradas
        CasinoService.Decision d = CasinoService.decide(line(3, 2, 1, 1), entry(10, 1, "TARGET", 0.10));
        assertEquals(CasinoService.Action.SUBMIT, d.action());
        assertEquals(2, d.line().cycle());
        assertEquals(0, d.line().used());
        assertEquals(1, d.line().hits());
        // vez 2: as 2 tentativas no stop → vez 3 mesmo sem o alvo
        CasinoService.Decision d2 = CasinoService.decide(d.line().counters(2, 2, 1, 10), entry(12, 2, "CLOSED", -1.0));
        assertEquals(CasinoService.Action.SUBMIT, d2.action());
        assertEquals(3, d2.line().cycle());
        assertEquals(1, d2.line().hits());
        // a mesma tentativa decidida de novo (ex.: esperando a previsão) não conta duas vezes
        CasinoService.Decision same = CasinoService.decide(d2.line(), entry(12, 2, "CLOSED", -1.0));
        assertEquals(CasinoService.Action.SUBMIT, same.action());
        assertEquals(3, same.line().cycle());
        // vez 3 (a última): alvo → termina
        CasinoService.Decision end = CasinoService.decide(d2.line().counters(3, 1, 1, 12), entry(13, 1, "TARGET", 0.10));
        assertEquals("DONE", end.line().status());
        assertEquals(2, end.line().hits());
    }

    private static com.jevforex.app.live.PredictionRepository.Prediction pred(String model, int h, String at,
                                                                               double down, double flat, double up) {
        return new com.jevforex.app.live.PredictionRepository.Prediction(1L, "metals", "XAUUSD", Instant.parse(at), "CONTROL",
                "SCHEDULE", model, h, "v", down, flat, up, "NO_TRADE", 1.0, 2000.0, 20, 1, "{}", null, null, null, null, null);
    }

    @Test
    void deixaComigo_ladoDaPrevisaoMaisRecenteDoB() {
        Instant now = Instant.parse("2026-10-23T14:20:00Z");
        var preds = List.of(
                pred("B", 15, "2026-10-23T14:00:00Z", 0.30, 0.50, 0.20),    // 15 min: queda > alta (LATERAL não impede)
                pred("B", 60, "2026-10-23T14:00:00Z", 0.20, 0.30, 0.50),
                pred("A", 15, "2026-10-23T14:02:00Z", 0.10, 0.10, 0.80));   // outro modelo: não conta
        CasinoService.Choice c = CasinoService.choose(preds, "XAUUSD", now);
        assertEquals("SELL", c.side(), "B 15 min antes de B 60 min");
        assertTrue(c.why().startsWith("B 15 min das 14:00"));
        // só a de 60 min serve → compra
        assertEquals("BUY", CasinoService.choose(List.of(preds.get(1)), "XAUUSD", now).side());
        // velha demais (mais de 75 min) ou de outro símbolo → espera
        assertNull(CasinoService.choose(preds, "XAUUSD", Instant.parse("2026-10-23T15:20:00Z")));
        assertNull(CasinoService.choose(preds, "BTCUSD", now));
        // do futuro em relação ao momento (backtest de um quarto anterior) → não vale
        assertNull(CasinoService.choose(preds, "XAUUSD", Instant.parse("2026-10-23T13:45:00Z")));
    }

    @Test
    void global_lucroOuPerdaDaRodada() {
        CasinoService.Global g = new CasinoService.Global(1.0, 2.0, 3);
        assertNull(CasinoService.globalHit(g, 0.99));
        assertTrue(CasinoService.globalHit(g, 1.0).startsWith("lucro global"));
        assertNull(CasinoService.globalHit(g, -1.99));
        assertTrue(CasinoService.globalHit(g, -2.0).startsWith("perda global"));
        assertNull(CasinoService.globalHit(new CasinoService.Global(null, null, 1), 50), "desligado");
        assertNull(CasinoService.globalHit(new CasinoService.Global(null, 2.0, 1), 50), "só a perda ligada");
    }

    @Test
    @SuppressWarnings("unchecked")
    void extrasDoCassino_ficamForaDosModelos() throws Exception {
        Map<String, Object> yml;
        try (var in = Files.newInputStream(Path.of("src/main/resources/application.yml"))) {
            yml = new org.yaml.snakeyaml.Yaml().load(in);
        }
        Map<String, List<String>> universe = (Map<String, List<String>>) ((Map<String, Object>) yml.get("experiment")).get("market-symbols");
        List<Map<String, Object>> groups = (List<Map<String, Object>>) ((Map<String, Object>) yml.get("casino")).get("groups");
        assertFalse(groups.isEmpty());
        for (Map<String, Object> g : groups) {
            String market = (String) g.get("market");
            List<String> pinned = universe.get(market);
            // sem universo fixo, o mercado usa "todos do silver": os extras entrariam em features, modelos e previsões
            assertTrue(pinned != null && !pinned.isEmpty(), "experiment.market-symbols." + market + " precisa ser fixo");
            for (Object s : (List<Object>) g.get("symbols")) {
                assertFalse(pinned.contains(String.valueOf(s)), s + " é extra do Cassino e não pode entrar no modelo de " + market);
            }
        }
    }

    @Test
    void ignoraAsTravasDeRisco_masNuncaAsTecnicasNemAPerdaDoDia() throws Exception {
        assertTrue(OrderService.CASINO_WAIVED.containsAll(OrderService.ROULETTE_WAIVED), "tudo o que a roleta ignora, o Cassino também");
        for (String risk : List.of("Posições abertas", "Par livre", "Mercado habilitado", "Risco aberto total", "ATR (última previsão)")) {
            assertTrue(OrderService.CASINO_WAIVED.contains(risk), risk);
        }
        String src = Files.readString(Path.of("src/main/java/com/jevforex/app/trade/OrderService.java"));
        for (String kept : List.of("Perda do dia", "Perda da semana", "EA conectado", "Modo × conta", "BLOCK / FLATTEN",
                "Cotação", "Lote", "Alvo em US$ × spread", "Stop do Cassino × spread", "Sessão até a saída por tempo")) {
            assertFalse(OrderService.CASINO_WAIVED.contains(kept), "trava que não pode ser ignorada: " + kept);
            assertTrue(src.contains("Check(\"" + kept + "\"") || src.contains("gate = \"" + kept + "\""), "trava não encontrada: " + kept);
        }
    }

    // ------------------------------------------------------------------ backtest

    private static final LocalDateTime T0 = LocalDateTime.parse("2026-10-23T13:00:00");

    /** Barras M1 de 13:00 a 13:59 com o preço dado por minuto (alta = baixa = abertura = fechamento, salvo exceções). */
    private static List<Bar> flat(double price) {
        List<Bar> out = new ArrayList<>();
        for (int i = 0; i < 60; i++) out.add(new Bar(T0.plusMinutes(i), price, price, price, price, 1, true));
        return out;
    }

    @Test
    void backtest_quatroQuartos_alvoStopTempoEAberta() {
        List<Bar> m1 = flat(100);
        // 13:00: compra; 13:03 sobe 1,2 → alvo (1,0 de distância)
        m1.set(3, new Bar(T0.plusMinutes(3), 100, 101.2, 100, 101, 1, true));
        // 13:15: 13:20 cai 2,5 → stop (2,0)
        m1.set(20, new Bar(T0.plusMinutes(20), 100, 100, 97.5, 98, 1, true));
        // 13:45: alvo e stop na mesma barra → STOP (pessimista)
        m1.set(50, new Bar(T0.plusMinutes(50), 100, 101.5, 97.9, 100, 1, true));
        Instant ref = Instant.parse("2026-10-23T14:02:00Z");   // os 4 quartos completos: 13:00, 13:15, 13:30, 13:45
        // usdPerPrice 0,1: alvo 1,0 de preço = US$ 0,10; stop 2,0 = US$ 0,20; spread 0
        List<CasinoBacktest.Quarter> q = CasinoBacktest.run(m1, ref, true, 1.0, 2.0, 0, 0, 0.1);
        assertEquals(4, q.size());
        assertEquals(Instant.parse("2026-10-23T13:00:00Z"), q.get(0).start());
        assertEquals("ALVO", q.get(0).result());
        assertEquals(0.10, q.get(0).usd(), 1e-9);
        assertEquals(4, q.get(0).minutes());
        assertEquals("STOP", q.get(1).result());
        assertEquals(-0.20, q.get(1).usd(), 1e-9);
        // 13:30: nem alvo nem stop até 13:45, mas a do 13:45 (alvo e stop juntos) a pega → STOP
        assertEquals("STOP", q.get(2).result());
        assertEquals("STOP", q.get(3).result());

        // saída por tempo de 10 min: a das 13:30 sai às 13:40 no preço (100 → 0)
        List<CasinoBacktest.Quarter> t = CasinoBacktest.run(m1, ref, true, 1.0, 2.0, 0, 10, 0.1);
        assertEquals("TEMPO", t.get(2).result());
        assertEquals(0.0, t.get(2).usd(), 1e-9);
    }

    @Test
    void backtest_spreadContraEVendaNoAsk() {
        List<Bar> m1 = flat(100);
        Instant ref = Instant.parse("2026-10-23T14:00:00Z");
        // compra com spread 0,3: entra a 100,3, sai no bid 100 → aberta com −0,3 de preço
        CasinoBacktest.Quarter buy = CasinoBacktest.run(m1, ref, true, 1.0, 2.0, 0.3, 0, 1.0).get(3);
        assertEquals("ABERTA", buy.result());
        assertEquals(-0.3, buy.usd(), 1e-9);
        // venda: entra no bid 100, o alvo precisa do ASK a 99 → o bid a 98,7 (99 − 0,3)
        m1.set(47, new Bar(T0.plusMinutes(47), 100, 100, 98.8, 99, 1, true));
        assertEquals("ABERTA", CasinoBacktest.run(m1, ref, false, 1.0, 2.0, 0.3, 0, 1.0).get(3).result(), "ask 99,1: não chegou");
        m1.set(47, new Bar(T0.plusMinutes(47), 100, 100, 98.6, 99, 1, true));
        assertEquals("ALVO", CasinoBacktest.run(m1, ref, false, 1.0, 2.0, 0.3, 0, 1.0).get(3).result());
    }

    @Test
    void backtest_semBarras_semDados() {
        List<CasinoBacktest.Quarter> q = CasinoBacktest.run(List.of(), Instant.parse("2026-10-23T14:00:00Z"), true, 1, 1, 0, 0, 1);
        assertTrue(q.stream().allMatch(x -> "SEM_DADOS".equals(x.result())));
    }
}
