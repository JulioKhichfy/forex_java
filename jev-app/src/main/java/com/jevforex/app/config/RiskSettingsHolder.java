package com.jevforex.app.config;

import com.jevforex.core.risk.RiskSettings;
import org.springframework.stereotype.Component;

/**
 * As regras de risco EM VIGOR. Começam com trading.risk (classpath + config/application.yml) e mudam quando o
 * operador grava a página Risco do dashboard — sem reiniciar. Quem decide ordens lê daqui a cada uso.
 */
@Component
public class RiskSettingsHolder {

    private volatile RiskSettings current;

    public RiskSettingsHolder(RiskSettings initial) {
        this.current = initial;
    }

    public RiskSettings get() {
        return current;
    }

    void set(RiskSettings settings) {
        if (settings == null) throw new IllegalArgumentException("configuração de risco vazia");
        this.current = settings;
    }
}
