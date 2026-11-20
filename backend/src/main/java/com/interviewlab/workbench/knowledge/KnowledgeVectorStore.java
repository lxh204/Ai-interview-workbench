package com.interviewlab.workbench.knowledge;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.interviewlab.workbench.knowledge.PassageSearch.PassageKey;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

@Component
class KnowledgeVectorStore {
    private static final int VECTOR_MAX_DIMENSIONS = 16_000;
    private static final int SEMANTIC_CANDIDATES = 24;

    private final NamedParameterJdbcTemplate named;
    private final ObjectMapper json;
    private final boolean postgres;

    KnowledgeVectorStore(JdbcTemplate jdbc, NamedParameterJdbcTemplate named, ObjectMapper json) {
        this.named = named;
        this.json = json;
        this.postgres = Boolean.TRUE.equals(jdbc.execute((ConnectionCallback<Boolean>) connection ->
            "PostgreSQL".equals(connection.getMetaData().getDatabaseProductName())));
    }

    boolean usesPgvector() {
        return postgres;
    }

    String column() {
        return postgres ? "embedding_vector" : "embedding";
    }

    String bindValue() {
        return postgres ? "cast(? as vector)" : "?";
    }

    String encode(double[] vector) {
        if (!postgres) {
            return serialize(vector);
        }
        float[] singlePrecision = asPgvector(vector);
        if (singlePrecision == null) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "模型返回的向量无法用于索引，请检查模型配置。");
        }
        return serialize(singlePrecision);
    }

    double[] decodeLegacy(String vector) {
        try {
            return json.readValue(vector, double[].class);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Cannot read document vector", ex);
        }
    }

    boolean anyIndexed(List<String> scope, String space) {
        return Boolean.TRUE.equals(named.queryForObject("""
            select exists (select 1 from knowledge_chunk c where c.embedding_vector is not null
                and c.embedding_space = :space
            """ + scopeFilter(scope) + ")", Map.of("scope", scope, "space", space), Boolean.class));
    }

    Map<PassageKey, Double> similarity(List<String> scope, String space, double[] queryVector) {
        float[] singlePrecision = asPgvector(queryVector);
        if (singlePrecision == null) {
            return Map.of();
        }

        // Materialization prevents the distance operator from seeing rows with incompatible vector dimensions.
        String sql = """
            with compatible as materialized (
                select c.document_id, c.chunk_index, c.content, c.embedding_vector
                from knowledge_chunk c
                where c.embedding_vector is not null and c.embedding_space = :space
                    and vector_dims(c.embedding_vector) = :dimensions and vector_norm(c.embedding_vector) > 0
            """ + scopeFilter(scope) + """
            ),
            nearest_distinct as (
                select distinct on (content) document_id, chunk_index,
                    embedding_vector <=> cast(:query as vector) as distance
                from compatible
                order by content, distance, document_id, chunk_index
            )
            select document_id, chunk_index, 1 - distance as similarity
            from nearest_distinct order by distance, document_id, chunk_index limit :limit
            """;
        var params = Map.of("scope", scope, "space", space, "dimensions", singlePrecision.length,
            "query", serialize(singlePrecision), "limit", SEMANTIC_CANDIDATES);
        return named.query(sql, params, rows -> {
            var results = new HashMap<PassageKey, Double>();
            while (rows.next()) {
                results.put(new PassageKey(rows.getString("document_id"), rows.getInt("chunk_index")), rows.getDouble("similarity"));
            }
            return results;
        });
    }

    private static String scopeFilter(List<String> scope) {
        return scope.isEmpty() ? "" : " and c.document_id in (:scope)\n";
    }

    private static float[] asPgvector(double[] vector) {
        if (vector == null || vector.length == 0 || vector.length > VECTOR_MAX_DIMENSIONS) {
            return null;
        }
        float[] converted = new float[vector.length];
        boolean nonzero = false;
        for (int i = 0; i < vector.length; i++) {
            float coordinate = (float) vector[i];
            if (!Float.isFinite(coordinate)) {
                return null;
            }
            converted[i] = coordinate;
            nonzero |= coordinate != 0;
        }
        return nonzero ? converted : null;
    }

    private String serialize(Object vector) {
        try {
            return json.writeValueAsString(vector);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Cannot encode document vector", ex);
        }
    }
}