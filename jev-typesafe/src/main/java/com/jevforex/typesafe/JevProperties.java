package com.jevforex.typesafe;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Configuração do Jev (bloco {@code jev} do application.yml).
 *
 * @param baseUrl        https://api.typesafe.ai
 * @param apiKey         chave vinda da variável TYPESAFE_API_KEY (preferível)
 * @param apiKeyFile     alternativa: caminho de um arquivo que contém só a chave
 * @param model          versão FIXA do modelo (ex.: jev-1.13.0), nunca o alias em treino/produção
 * @param timeoutSeconds tempo máximo por chamada
 * @param maxRetries     novas tentativas para 429, 529 e 5xx
 */
@ConfigurationProperties(prefix = "jev")
public record JevProperties(
        String baseUrl,
        String apiKey,
        String apiKeyFile,
        String model,
        int timeoutSeconds,
        int maxRetries) {

    public JevProperties {
        if (baseUrl == null || baseUrl.isBlank()) baseUrl = "https://api.typesafe.ai";
        if (model == null || model.isBlank()) model = "jev-latest";
        if (timeoutSeconds <= 0) timeoutSeconds = 20;
        if (maxRetries < 0) maxRetries = 2;
    }

    /** Resolve a chave: variável de ambiente primeiro, depois o arquivo. Nunca loga o valor. */
    public String resolveApiKey() {
        if (apiKey != null && !apiKey.isBlank()) {
            return apiKey.trim();
        }
        if (apiKeyFile != null && !apiKeyFile.isBlank()) {
            try {
                String k = Files.readString(Path.of(apiKeyFile)).trim();
                if (!k.isEmpty()) return k;
            } catch (IOException e) {
                throw new IllegalStateException("Não consegui ler jev.api-key-file: " + apiKeyFile, e);
            }
        }
        throw new IllegalStateException(
                "Chave do Jev não configurada. Defina TYPESAFE_API_KEY ou JEV_API_KEY_FILE (veja o README).");
    }

    /** Mostra só o começo e o fim da chave, para conferência em log. */
    public String maskedKey() {
        try {
            String k = resolveApiKey();
            return k.length() <= 12 ? "****" : k.substring(0, 7) + "…" + k.substring(k.length() - 4);
        } catch (IllegalStateException e) {
            return "(não configurada)";
        }
    }
}
