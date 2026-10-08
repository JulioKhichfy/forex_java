package com.jevforex.app.config;

import com.jevforex.core.Instrument;
import com.jevforex.core.risk.RiskSettings;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Map;

/** Valida a configuração de risco na inicialização: valor inválido = o app nem sobe. */
@Configuration
public class RiskConfig {

    /** Catálogo de instrumentos (um objeto próprio evita a injeção "mágica" de Map pelo Spring). */
    public record InstrumentCatalog(Map<String, Instrument> bySymbol) {
    }

    @Bean
    public RiskSettings riskSettings(TradingProperties trading) {
        return trading.toRiskSettings();
    }

    @Bean
    public InstrumentCatalog instrumentCatalog(TradingProperties trading) {
        return new InstrumentCatalog(trading.toInstruments());
    }
}
