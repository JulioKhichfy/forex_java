package com.jevforex.app.trade;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class EventDeskServiceTest {

    @Test
    void ladoDaMoedaEmCadaSimbolo() {
        assertEquals(1, EventDeskService.dirOf("EURUSD", "fx", "EUR"), "moeda na base: forte = sobe");
        assertEquals(-1, EventDeskService.dirOf("EURUSD", "fx", "USD"), "moeda cotada: forte = cai");
        assertEquals(-1, EventDeskService.dirOf("USDCAD", "fx", "CAD"));
        assertEquals(0, EventDeskService.dirOf("EURUSD", "fx", "JPY"), "não está no par");
        assertEquals(-1, EventDeskService.dirOf("XAUUSD", "metals", "USD"), "dólar forte = ouro cai");
        assertEquals(-1, EventDeskService.dirOf("BTCUSD", "crypto", "USD"));
        assertEquals(0, EventDeskService.dirOf("XAUUSD", "metals", "EUR"));
        assertEquals(0, EventDeskService.dirOf("US500", "indices", "USD"), "índices: sem regra (só o modo modelo)");
    }

    @Test
    void consenso_eDado_comMaiorEhBom() {
        // juros: previsto 2,50 > anterior 2,25, "maior é bom" → favorável à moeda
        assertEquals(1, EventDeskService.consensus(2.50, 2.25, 1));
        // desemprego: previsto acima do anterior e "maior é ruim" → desfavorável
        assertEquals(-1, EventDeskService.consensus(4.4, 4.2, -1));
        assertEquals(0, EventDeskService.consensus(2.25, 2.25, 1), "igual ao anterior");
        assertNull(EventDeskService.consensus(null, 2.25, 1), "sem previsto (comum nas decisões de juros)");
        assertNull(EventDeskService.consensus(2.5, 2.25, null), "sem saber se maior é bom");
        // dado: o impacto do MT5 manda; sem ele, real × previsto
        assertEquals(1, EventDeskService.surpriseDir("POSITIVE", 1.0, 2.0, 1));
        assertEquals(-1, EventDeskService.surpriseDir("NEGATIVE", null, null, null));
        assertEquals(-1, EventDeskService.surpriseDir("NA", 230.0, 220.0, -1), "pedidos de seguro-desemprego acima = ruim");
        assertNull(EventDeskService.surpriseDir("NA", null, 220.0, -1));
    }

    @Test
    void ladoDaOrdem_eEventoPrincipal() {
        assertEquals("SELL", EventDeskService.side(1, -1), "moeda forte, moeda cotada (USD em EURUSD) → vende EURUSD");
        assertEquals("BUY", EventDeskService.side(-1, -1), "dólar fraco → compra ouro");
        var evs = List.<Map<String, Object>>of(Map.of("code", "boc-rate-statement"), Map.of("code", "boc-interest-rate-decision"));
        assertEquals("boc-interest-rate-decision", EventDeskService.mainEvent(evs).get("code"), "a decisão de juros manda");
    }
}
