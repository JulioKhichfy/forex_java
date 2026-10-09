package com.jevforex.collect.mt5;

import com.jevforex.core.BrokerSymbols;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.file.Path;

/**
 * Bloco {@code mt5} do application.yml.
 *
 * @param commonFiles           pasta Common\Files\jev do MetaTrader (compartilhada por todos os terminais)
 * @param importEnabled         importa automaticamente no modo servidor
 * @param importIntervalSeconds intervalo entre varreduras do inbox
 * @param symbolSuffix          sufixo dos símbolos na corretora (Exness: "m" → EURUSDm)
 * @param expectedAccount       conta (login) aceita; 0 = não confere. Arquivos e EA de outra conta são recusados
 * @param expectedServer        servidor aceito (ex.: Exness-MT5Trial11); vazio = não confere
 * @param heartbeatStaleSeconds heartbeat do EA mais velho que isto = EA fora (documento mestre: 30 s)
 * @param candleStaleSeconds    última barra M1 mais velha que isto = candles atrasados (aviso no status)
 */
@ConfigurationProperties(prefix = "mt5")
public record Mt5Properties(
        String commonFiles,
        boolean importEnabled,
        int importIntervalSeconds,
        String symbolSuffix,
        long expectedAccount,
        String expectedServer,
        int heartbeatStaleSeconds,
        int candleStaleSeconds) {

    public Mt5Properties {
        if (commonFiles == null || commonFiles.isBlank()) {
            commonFiles = System.getProperty("user.home") + "/AppData/Roaming/MetaQuotes/Terminal/Common/Files/jev";
        }
        if (importIntervalSeconds <= 0) importIntervalSeconds = 2;
        if (symbolSuffix == null) symbolSuffix = "";
        if (expectedServer == null) expectedServer = "";
        if (heartbeatStaleSeconds <= 0) heartbeatStaleSeconds = 30;
        if (candleStaleSeconds <= 0) candleStaleSeconds = 150;
    }

    /** Onde os programas MQL5 deixam arquivos prontos. */
    public Path inbox() {
        return Path.of(commonFiles).resolve("inbox");
    }

    /** Arquivos que falharam na importação (com um .erro.txt explicando). */
    public Path errorDir() {
        return Path.of(commonFiles).resolve("error");
    }

    public BrokerSymbols symbols() {
        return new BrokerSymbols(symbolSuffix);
    }

    /**
     * Confere conta e servidor. Na mesma máquina há outros terminais (Common\Files é compartilhada):
     * dado de outra conta não pode entrar como se fosse desta.
     *
     * @return null se aceito; senão, o motivo da recusa
     */
    public String rejectAccount(long account, String server) {
        if (expectedAccount > 0 && account != expectedAccount) {
            return "conta " + account + " diferente da esperada (mt5.expected-account=" + expectedAccount + ")";
        }
        if (!expectedServer.isBlank() && !expectedServer.equalsIgnoreCase(server == null ? "" : server.trim())) {
            return "servidor " + server + " diferente do esperado (mt5.expected-server=" + expectedServer + ")";
        }
        return null;
    }
}
