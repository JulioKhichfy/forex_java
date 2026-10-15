package com.jevforex.app.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.jevforex.core.Market;
import com.jevforex.core.risk.RiskSettings;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.FileSystemResource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RiskEditorTest {

    private static RiskSettings sample() {
        var global = new RiskSettings.Global(8.0, 1.5, 12.0, 20.0, 35.0, 2, 15.0, RiskSettings.MinLotPolicy.ALLOW_UP_TO_CAP);
        var fx = new RiskSettings.MarketRisk(true, 5.0, 10.0, 2, 1.0);
        var idx = new RiskSettings.MarketRisk(true, 4.0, 8.0, 1, 0.0);
        return new RiskSettings(global, Map.of(Market.FX, fx, Market.INDICES, idx), new RiskSettings.Exits(1.5, 2.0),
                new RiskSettings.Orders(0.01, 0.03, 60, 20, 30, 30, 90, 10, 2, 2.0, 300),
                Map.of("conservador", new RiskSettings.LossProfile("Conservador", 6, 12)),
                new RiskSettings.MoneyExits(true, 3.0, 6.0, true, 2.0, 1.5, 0.25));
    }

    @Test
    void yamlGravado_lidoPeloSpring_reproduzAsMesmasRegras(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("config/application.yml");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "server:\n  port: 8080\n");   // chaves do operador que não são de risco ficam
        ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
        RiskEditor editor = new RiskEditor(new RiskSettingsHolder(sample()), mapper, null, file.toString());
        editor.writeYaml(sample());

        StandardEnvironment env = new StandardEnvironment();
        new YamlPropertySourceLoader().load("teste", new FileSystemResource(file))
                .forEach(ps -> env.getPropertySources().addFirst(ps));
        TradingProperties.Risk risk = Binder.get(env).bind("trading.risk", TradingProperties.Risk.class).get();
        RiskSettings back = new TradingProperties(null, null, risk, null).toRiskSettings();

        assertEquals(sample(), back);
        assertEquals("8080", env.getProperty("server.port"));
        String text = Files.readString(file);
        assertTrue(text.contains("max-risk-per-trade-pct"), text);
        assertTrue(text.contains("trail-distance-usd: 1.5"), "stop em US$ e trailing gravados em kebab-case");
        assertTrue(!text.contains("padrao"), "o perfil padrão vem de global e não é gravado à parte");
    }

    @Test
    void valorInvalido_recusado_semGravar(@TempDir Path dir) {
        Path file = dir.resolve("config/application.yml");
        ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
        RiskEditor editor = new RiskEditor(new RiskSettingsHolder(sample()), mapper, null, file.toString());
        @SuppressWarnings("unchecked")
        Map<String, Object> json = mapper.convertValue(sample(), Map.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> orders = (Map<String, Object>) json.get("orders");
        orders.put("maxLot", 0.001);   // menor que o lote padrão
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> editor.update(json, "teste", null));
        assertTrue(e.getMessage().contains("default-lot"), e.getMessage());
        assertTrue(!Files.exists(file));
    }
}
