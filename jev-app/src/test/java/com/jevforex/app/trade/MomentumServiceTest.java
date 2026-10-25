package com.jevforex.app.trade;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MomentumServiceTest {

    @Test
    void todasAsTravas_comAsSaidasDaRegra() throws Exception {
        String src = Files.readString(Path.of("src/main/java/com/jevforex/app/trade/MomentumService.java"));
        assertTrue(src.contains("new OrderService.Fixed("), "as saídas em que a regra foi validada");
        assertFalse(src.contains("Roulette"), "o momentum não pode usar as exceções da roleta nem do Cassino");
        // 24/7: o stop vem do ATR de 1 h dos candles; a trava do ATR da previsão (que só sai em dias úteis, 0–20 h) não se aplica
        String os = Files.readString(Path.of("src/main/java/com/jevforex/app/trade/OrderService.java"));
        assertTrue(os.contains("\"não usado: stop da regra (ATR de 1 h dos candles)\""));
    }

    @Test
    void padroes_sao_a_regra_do_estudo() {
        // tools/estudo_momentum.py, a melhor na escolha e confirmada: top 2% · stop 1,5 ATR · alvo 1,5 · 24 h
        MomentumProperties p = new MomentumProperties(null, null, null, null, null, null, null, null, null);
        assertEquals(List.of("BTCUSD"), p.symbols());
        assertEquals(720, p.lookbackHours());
        assertEquals(2.0, p.topPct());
        assertEquals(1.5, p.stopAtr());
        assertEquals(1.5, p.targetR());
        assertEquals(1440, p.closeAfterMinutes());
    }
}
