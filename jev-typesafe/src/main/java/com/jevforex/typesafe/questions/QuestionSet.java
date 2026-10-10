package com.jevforex.typesafe.questions;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.Map;

/**
 * Um conjunto versionado de perguntas ao Jev, carregado de resources/question-sets/*.json.
 *
 * <p>As perguntas são escritas em INGLÊS (é o que vai para o Jev). Campos cujo nome começa com
 * "_" (ex.: "_pt", com a tradução em português) são documentação: são removidos antes do envio.</p>
 *
 * @param code       identificador com versão (ex.: cb-text-v1). Mudou uma palavra → nova versão.
 * @param docType    tipo de documento a que se aplica (cb_text, headline, …)
 * @param definition JSON completo, incluindo os campos "_pt"
 * @param sha256     hash das perguntas enviadas (sem "_pt"), gravado em cada chamada
 */
public record QuestionSet(String code, String docType, JsonNode definition, String sha256) {

    public static QuestionSet from(JsonNode definition, ObjectMapper mapper) {
        String code = definition.path("code").asText(null);
        if (code == null || code.isBlank()) {
            throw new IllegalArgumentException("question set sem 'code'");
        }
        if (!definition.path("questions").isObject() || definition.path("questions").isEmpty()) {
            throw new IllegalArgumentException(code + ": 'questions' vazio");
        }
        JsonNode api = stripDocFields(definition.path("questions").deepCopy());
        String sha = sha256(api.toString());
        return new QuestionSet(code, definition.path("doc_type").asText("generic"), definition, sha);
    }

    /** Perguntas no formato da API, sem os campos de documentação. */
    public JsonNode apiQuestions() {
        return stripDocFields(definition.path("questions").deepCopy());
    }

    /**
     * Que trechos o conjunto avalia e com que contexto (bloco opcional "input"; não é enviado ao Jev).
     *
     * @return tipos de documento (vazio = todos) e o modo de contexto anterior (null = sem contexto)
     */
    public Input input() {
        JsonNode in = definition.path("input");
        java.util.List<String> kinds = new java.util.ArrayList<>();
        in.path("doc_kinds").forEach(k -> kinds.add(k.asText()));
        String previous = in.path("previous").asText(null);
        if (previous != null && !previous.equals(Input.ALIGNED_CHUNK)) {
            throw new IllegalArgumentException(code + ": input.previous desconhecido: " + previous);
        }
        return new Input(java.util.List.copyOf(kinds), previous);
    }

    /** Ver {@link #input()}. */
    public record Input(java.util.List<String> docKinds, String previous) {
        /** previous_text = trecho de mesma posição relativa na divulgação anterior do mesmo emissor e tipo. */
        public static final String ALIGNED_CHUNK = "aligned_chunk";

        public boolean withPrevious() {
            return previous != null;
        }
    }

    /** Número de níveis declarado num Score (para normalizar o resultado). */
    public int scoreLevels(String questionId) {
        JsonNode c = definition.path("questions").path(questionId).path("criteria");
        return c.isArray() ? c.size() : 0;
    }

    /** Tradução em português da instrução de uma pergunta, se existir. */
    public String portuguese(String questionId) {
        return definition.path("questions").path(questionId).path("_pt").asText("");
    }

    public Iterator<Map.Entry<String, JsonNode>> questions() {
        return definition.path("questions").fields();
    }

    static JsonNode stripDocFields(JsonNode node) {
        if (node instanceof ObjectNode obj) {
            java.util.List<String> drop = new java.util.ArrayList<>();
            obj.fieldNames().forEachRemaining(n -> {
                if (n.startsWith("_")) drop.add(n);
            });
            obj.remove(drop);
            obj.elements().forEachRemaining(QuestionSet::stripDocFields);
        } else if (node instanceof ArrayNode arr) {
            arr.forEach(QuestionSet::stripDocFields);
        }
        return node;
    }

    static String sha256(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
