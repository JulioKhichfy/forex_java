package com.jevforex.typesafe.questions;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.util.Collection;
import java.util.Map;
import java.util.TreeMap;

/** Carrega todos os conjuntos de perguntas de classpath:question-sets/*.json na inicialização. */
@Component
public class QuestionSetRegistry {

    private static final Logger log = LoggerFactory.getLogger(QuestionSetRegistry.class);

    private final Map<String, QuestionSet> byCode = new TreeMap<>();

    public QuestionSetRegistry(ObjectMapper mapper) throws IOException {
        Resource[] resources = new PathMatchingResourcePatternResolver()
                .getResources("classpath*:question-sets/*.json");
        for (Resource r : resources) {
            try (InputStream in = r.getInputStream()) {
                JsonNode def = mapper.readTree(in);
                QuestionSet qs = QuestionSet.from(def, mapper);
                byCode.put(qs.code(), qs);
                log.debug("question set carregado: {} ({} perguntas)", qs.code(), def.path("questions").size());
            }
        }
        if (byCode.isEmpty()) {
            log.warn("Nenhum question set encontrado em classpath:question-sets/");
        }
    }

    public QuestionSet get(String code) {
        QuestionSet qs = byCode.get(code);
        if (qs == null) {
            throw new IllegalArgumentException("Question set desconhecido: " + code + ". Disponíveis: " + byCode.keySet());
        }
        return qs;
    }

    public Collection<QuestionSet> all() {
        return byCode.values();
    }
}
