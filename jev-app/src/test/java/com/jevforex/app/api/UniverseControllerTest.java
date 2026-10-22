package com.jevforex.app.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UniverseControllerTest {

    private static ObjectNode history(double upPos, int nPos, double upNeg, int nNeg) {
        return new ObjectMapper().createObjectNode().put("upPos15", upPos).put("nPos", nPos).put("upNeg15", upNeg).put("nNeg", nNeg);
    }

    private static Map<String, Object> event(Double forecast, Double previous) {
        Map<String, Object> e = new HashMap<>();
        e.put("forecast", forecast);
        e.put("previous", previous);
        return e;
    }

    @Test
    void inclinacao_ladoDoConsenso_maisOHistorico() {
        // consenso espera ACIMA do anterior; quando veio acima, o símbolo subiu 80% das vezes → verde
        assertEquals("up", UniverseController.lean(event(3.2, 3.0), history(0.80, 20, 0.30, 20)).get("dir"));
        // consenso espera ABAIXO; quando veio abaixo, subiu só 30% → vermelho
        assertEquals("down", UniverseController.lean(event(2.8, 3.0), history(0.80, 20, 0.30, 20)).get("dir"));
        // sem padrão (50%) → neutro
        assertEquals("neutral", UniverseController.lean(event(3.2, 3.0), history(0.50, 20, 0.50, 20)).get("dir"));
    }

    @Test
    void semDadosBastantes_ficaNeutro() {
        assertEquals("neutral", UniverseController.lean(event(3.2, 3.0), null).get("dir"), "sem histórico");
        assertEquals("neutral", UniverseController.lean(event(null, 3.0), history(0.8, 20, 0.3, 20)).get("dir"), "sem previsto");
        assertEquals("neutral", UniverseController.lean(event(3.0, 3.0), history(0.8, 20, 0.3, 20)).get("dir"), "previsto = anterior");
        Map<String, Object> few = UniverseController.lean(event(3.2, 3.0), history(0.9, 5, 0.3, 20));
        assertEquals("neutral", few.get("dir"));
        assertTrue(((String) few.get("why")).contains("poucos casos"));
    }
}
