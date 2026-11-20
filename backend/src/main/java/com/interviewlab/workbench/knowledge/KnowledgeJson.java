package com.interviewlab.workbench.knowledge;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

@Component
class KnowledgeJson {
    private final ObjectMapper json;

    KnowledgeJson(ObjectMapper json) {
        this.json = json;
    }

    String write(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Cannot encode knowledge data", ex);
        }
    }

    <T> T read(String value, TypeReference<T> type) {
        try {
            return json.readValue(value, type);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Cannot decode knowledge data", ex);
        }
    }
}
