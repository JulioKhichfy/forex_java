package com.jevforex.typesafe.model;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Resposta do POST /v1/systemone, com leitura tipada por pergunta.
 *
 * <p>Formato oficial (docs.typesafe.ai/api):</p>
 * <pre>
 * noul:   { "type":"noul",   "noul": 0.9 }
 * choice: { "type":"choice", "choice":"hawkish", "probabilities":{...}, "confidence":0.78 }
 * score:  { "type":"score",  "score": 1.4, "legend":{"0":"...","1":"..."},
 *           "probabilities":{"0":0.0,"1":0.6,"2":0.4}, "confidence":0.8 }
 * </pre>
 * <p>Atenção: os níveis do Score começam em 0. Com 4 níveis, o score vai de 0 a 3.</p>
 */
public record JevResponse(JsonNode request, JsonNode raw, long latencyMs) {

    public String model() {
        return raw.path("model").asText(null);
    }

    public int inputTokens() {
        return raw.path("usage").path("input_tokens").asInt(0);
    }

    public JsonNode answers() {
        return raw.path("answers");
    }

    public Optional<ChoiceAnswer> choice(String id) {
        JsonNode a = answers().get(id);
        if (a == null || !"choice".equals(a.path("type").asText())) return Optional.empty();
        return Optional.of(new ChoiceAnswer(
                a.path("choice").asText(),
                toMap(a.path("probabilities")),
                a.path("confidence").asDouble(Double.NaN)));
    }

    public Optional<ScoreAnswer> score(String id) {
        JsonNode a = answers().get(id);
        if (a == null || !"score".equals(a.path("type").asText())) return Optional.empty();
        Map<String, String> legend = new LinkedHashMap<>();
        a.path("legend").fields().forEachRemaining(e -> legend.put(e.getKey(), e.getValue().asText()));
        return Optional.of(new ScoreAnswer(
                a.path("score").asDouble(Double.NaN),
                legend,
                toMap(a.path("probabilities")),
                a.path("confidence").asDouble(Double.NaN)));
    }

    public Optional<Double> noul(String id) {
        JsonNode a = answers().get(id);
        if (a == null || !"noul".equals(a.path("type").asText())) return Optional.empty();
        return Optional.of(a.path("noul").asDouble(Double.NaN));
    }

    private static Map<String, Double> toMap(JsonNode n) {
        Map<String, Double> m = new LinkedHashMap<>();
        Iterator<Map.Entry<String, JsonNode>> it = n.fields();
        while (it.hasNext()) {
            var e = it.next();
            m.put(e.getKey(), e.getValue().asDouble());
        }
        return m;
    }

    /** Escolha entre opções: opção vencedora, distribuição e confiança. */
    public record ChoiceAnswer(String choice, Map<String, Double> probabilities, double confidence) {
        public double p(String option) {
            return probabilities.getOrDefault(option, 0.0);
        }
    }

    /** Posição numa escala ordenada (0 .. níveis-1). */
    public record ScoreAnswer(double score, Map<String, String> legend, Map<String, Double> probabilities,
                              double confidence) {
        public int levels() {
            return Math.max(legend.size(), probabilities.size());
        }

        /** Score normalizado entre 0 e 1. */
        public double normalized() {
            int n = levels();
            return n <= 1 ? 0 : score / (n - 1);
        }
    }
}
