package com.jevforex.app.trade;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HighFrequencyServiceTest {

    @Test
    void ignoraAsTravasDeRisco_masNuncaAsTecnicasNemAPerdaDoDia() throws Exception {
        assertTrue(OrderService.HF_WAIVED.containsAll(OrderService.ROULETTE_WAIVED), "tudo o que a roleta ignora, a alta frequência também");
        for (String risk : List.of("Posições abertas", "Par livre", "Mercado habilitado", "Risco aberto total")) {
            assertTrue(OrderService.HF_WAIVED.contains(risk), risk);
        }
        String src = Files.readString(Path.of("src/main/java/com/jevforex/app/trade/OrderService.java"));
        for (String kept : List.of("Perda do dia", "Perda da semana", "EA conectado", "Modo × conta", "BLOCK / FLATTEN",
                "Cotação", "ATR (última previsão)", "Lote", "Alvo em US$ × spread")) {
            assertFalse(OrderService.HF_WAIVED.contains(kept), "trava que não pode ser ignorada: " + kept);
            assertTrue(src.contains("new Check(\"" + kept + "\""), "trava não encontrada: " + kept);
        }
    }
}
