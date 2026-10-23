package com.jevforex.app.trade;

import com.jevforex.features.scan.Bar;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CasinoServiceTest {

    private static CasinoService.Line line(int attempts, int used) {
        return new CasinoService.Line("XAUUSD", "metals", "BUY", null, 0.10, 1.0, attempts, null, true, used, "RUNNING",
                null, Instant.now());
    }

    private static CasinoService.Entry entry(int attempt, String status, Double result) {
        return new CasinoService.Entry(1, attempt, status, result, "motivo");
    }

    @Test
    void tentativas_ateOAlvo() {
        assertEquals(CasinoService.Action.SUBMIT, CasinoService.decide(line(3, 0), null).action(), "ligou: abre na hora");
        assertEquals(CasinoService.Action.WAIT, CasinoService.decide(line(3, 1), entry(1, "SENT", null)).action());
        assertEquals(CasinoService.Action.SUBMIT, CasinoService.decide(line(3, 1), entry(1, "CLOSED", -1.0)).action(),
                "fechou no stop com tentativas sobrando: tenta de novo");
        CasinoService.Decision hit = CasinoService.decide(line(3, 2), entry(2, "TARGET", 0.10));
        assertEquals(CasinoService.Action.FINISH, hit.action());
        assertEquals("TARGET", hit.status(), "bateu o alvo: a linha termina");
        CasinoService.Decision out = CasinoService.decide(line(3, 3), entry(3, "CLOSED", -1.0));
        assertEquals("EXHAUSTED", out.status(), "acabaram as tentativas");
        assertEquals("STOPPED", CasinoService.decide(line(3, 1), entry(1, "FAILED", null)).status(), "falha: falhar fechado");
        assertEquals("STOPPED", CasinoService.decide(line(3, 1), entry(1, "REFUSED", null)).status());
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
