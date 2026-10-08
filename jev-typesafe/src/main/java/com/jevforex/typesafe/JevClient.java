package com.jevforex.typesafe;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jevforex.typesafe.model.JevResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.net.http.HttpClient;
import java.time.Duration;

/**
 * Cliente HTTP do Jev.
 *
 * <p>Endpoints usados:</p>
 * <ul>
 *   <li>{@code GET  /v1/models}     — testa a chave sem gastar créditos com texto</li>
 *   <li>{@code POST /v1/systemone}  — envia state + perguntas tipadas, recebe probabilidades</li>
 * </ul>
 *
 * <p>Retry com backoff exponencial em 429, 529 e 5xx, respeitando o cabeçalho Retry-After.</p>
 */
@Component
public class JevClient {

    private static final Logger log = LoggerFactory.getLogger(JevClient.class);

    private final JevProperties props;
    private final ObjectMapper mapper;
    private volatile RestClient http;

    public JevClient(JevProperties props, ObjectMapper mapper) {
        this.props = props;
        this.mapper = mapper;
    }

    /** Cria o RestClient só na primeira chamada, para o app subir mesmo sem chave configurada. */
    private RestClient http() {
        RestClient c = http;
        if (c == null) {
            synchronized (this) {
                if (http == null) {
                    HttpClient jdk = HttpClient.newBuilder()
                            .connectTimeout(Duration.ofSeconds(10))
                            .build();
                    JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(jdk);
                    factory.setReadTimeout(Duration.ofSeconds(props.timeoutSeconds()));
                    http = RestClient.builder()
                            .baseUrl(props.baseUrl())
                            .requestFactory(factory)
                            .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + props.resolveApiKey())
                            .defaultHeader(HttpHeaders.USER_AGENT, "jev-forex/0.1")
                            .build();
                }
                c = http;
            }
        }
        return c;
    }

    public String defaultModel() {
        return props.model();
    }

    /** GET /v1/models — lista os modelos disponíveis para a sua chave. */
    public JsonNode listModels() {
        return withRetry("GET /v1/models", () -> http().get().uri("/v1/models")
                .accept(MediaType.APPLICATION_JSON)
                .retrieve()
                .body(JsonNode.class));
    }

    /**
     * POST /v1/systemone.
     *
     * @param model     modelo; null usa o configurado em jev.model
     * @param state     contexto (texto ou objeto JSON)
     * @param questions perguntas já sem os campos "_pt" (ver QuestionSet#apiQuestions)
     */
    public JevResponse systemOne(String model, JsonNode state, JsonNode questions) {
        ObjectNode body = mapper.createObjectNode();
        body.put("model", model == null ? props.model() : model);
        body.set("state", state);
        body.set("questions", questions);
        long t0 = System.nanoTime();
        JsonNode resp = withRetry("POST /v1/systemone", () -> http().post().uri("/v1/systemone")
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(JsonNode.class));
        long ms = (System.nanoTime() - t0) / 1_000_000;
        return new JevResponse(body, resp, ms);
    }

    private <T> T withRetry(String what, java.util.function.Supplier<T> call) {
        int attempt = 0;
        long delayMs = 500;
        while (true) {
            try {
                return call.get();
            } catch (RestClientResponseException e) {
                int status = e.getStatusCode().value();
                String requestId = e.getResponseHeaders() == null ? null
                        : e.getResponseHeaders().getFirst("x-typesafe-request-id");
                boolean retryable = status == 429 || status == 529 || status >= 500;
                if (!retryable || attempt >= props.maxRetries()) {
                    JevApiException ex = new JevApiException(status, requestId, e.getResponseBodyAsString(),
                            what + " falhou com HTTP " + status);
                    log.warn("{} — {} (request id: {})", ex.getMessage(), ex.hint(), requestId);
                    throw ex;
                }
                long wait = retryAfterMs(e).orElse(delayMs);
                log.info("{} retornou {}; nova tentativa em {} ms", what, status, wait);
                sleep(wait);
                attempt++;
                delayMs = Math.min(delayMs * 2, 5_000);
            }
        }
    }

    private static java.util.Optional<Long> retryAfterMs(RestClientResponseException e) {
        if (e.getResponseHeaders() == null) return java.util.Optional.empty();
        String v = e.getResponseHeaders().getFirst(HttpHeaders.RETRY_AFTER);
        if (v == null) return java.util.Optional.empty();
        try {
            return java.util.Optional.of(Math.min(Long.parseLong(v.trim()) * 1000L, 30_000L));
        } catch (NumberFormatException ignored) {
            return java.util.Optional.empty();
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrompido", ie);
        }
    }

    public String maskedKey() {
        return props.maskedKey();
    }

    public ObjectMapper mapper() {
        return mapper;
    }
}
