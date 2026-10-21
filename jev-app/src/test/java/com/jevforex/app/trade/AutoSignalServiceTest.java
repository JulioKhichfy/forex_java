package com.jevforex.app.trade;

import com.jevforex.app.live.LiveProperties;
import com.jevforex.app.live.PredictionRepository;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AutoSignalServiceTest {

    private static final LiveProperties.SignalRule OURO = new LiveProperties.SignalRule("B", 15, 0.45, 0.15, true, null);

    private static PredictionRepository.Prediction pred(String model, int h, String signal) {
        return new PredictionRepository.Prediction(1L, "metals", "XAUUSD", Instant.now(), "EVENT", "EVENT", model, h, "v",
                0.1, 0.3, 0.6, signal, 2.0, 4100.0, 20, 10, "{}", Instant.now(), null, null, null, null);
    }

    @Test
    void soOSinalDaRegra_viraOrdem() {
        assertTrue(AutoSignalService.isRuleSignal(pred("B", 15, "BUY"), OURO));
        assertFalse(AutoSignalService.isRuleSignal(pred("B", 15, "NO_TRADE"), OURO), "sem sinal");
        assertFalse(AutoSignalService.isRuleSignal(pred("B", 60, "BUY"), OURO), "outro horizonte (gate 4)");
        assertFalse(AutoSignalService.isRuleSignal(pred("A", 15, "SELL"), OURO), "outro modelo");
    }

    @Test
    void todasAsTravasValem_semAExcecaoDaRoleta() throws Exception {
        String src = Files.readString(Path.of("src/main/java/com/jevforex/app/trade/AutoSignalService.java"));
        assertTrue(src.contains("orders.submit(p.symbol(), p.signal(), lot);"), "submit sem OrderService.Roulette = todas as travas");
        assertFalse(src.contains("new OrderService.Roulette("), "o automático não pode usar as exceções da roleta");
    }
}
