package com.interviewlab.workbench.knowledge;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.interviewlab.workbench.ai.ModelGateway;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@EnabledIfEnvironmentVariable(named = "WORKBENCH_VECTOR_TEST_URL", matches = ".+")
class PostgresKnowledgeVectorTest {
    @Test
    void storesNativeVectorsAndCombinesScopedPgvectorResultsWithKeywordCitations() {
        String url = System.getenv("WORKBENCH_VECTOR_TEST_URL");
        String user = System.getenv().getOrDefault("WORKBENCH_VECTOR_TEST_USER", "postgres");
        String password = System.getenv().getOrDefault("WORKBENCH_VECTOR_TEST_PASSWORD", "");
        String schema = "knowledge_test_" + UUID.randomUUID().toString().replace("-", "");

        var admin = new JdbcTemplate(new DriverManagerDataSource(url, user, password));
        admin.execute("create schema " + schema);
        try {
            var dataSource = new DriverManagerDataSource(url, user, password);
            var properties = new Properties();
            properties.setProperty("currentSchema", schema + ",public");
            dataSource.setConnectionProperties(properties);
            var jdbc = new JdbcTemplate(dataSource);
            var named = new NamedParameterJdbcTemplate(jdbc);
            var transactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
            jdbc.execute("""
                create table knowledge_document (
                  id varchar(36) primary key, title text, category text, filename text, created_at timestamptz default current_timestamp,
                  original_file bytea, original_text text, size_bytes bigint, question_count bigint default 0, access_count bigint default 0
                )
                """);
            jdbc.execute("""
                create table knowledge_chunk (
                  document_id varchar(36) not null references knowledge_document(id) on delete cascade,
                  chunk_index integer not null, content varchar(1200) not null, embedding text, embedding_space varchar(400),
                  primary key (document_id, chunk_index)
                )
                """);
            new ResourceDatabasePopulator(new ClassPathResource("knowledge-postgres.sql")).execute(dataSource);

            var model = mock(ModelGateway.class);
            var store = new KnowledgeVectorStore(jdbc, named, new ObjectMapper());
            assertThat(store.usesPgvector()).isTrue();
            var documents = new KnowledgeDocuments(jdbc, named, transactions, store, model);
            var answers = new KnowledgeAnswers(documents, model, store);
            when(model.enabled()).thenReturn(true);
            when(model.embeddingsEnabled()).thenReturn(true);
            when(model.embeddingSpace()).thenReturn("fixture/model-a");
            when(model.complete(anyString(), anyString())).thenReturn("匹配到知识库证据 [1]。");
            when(model.embed(List.of("read concurrency"))).thenReturn(List.of(new double[]{1, 0, 0}));
            when(model.embed(List.of("server version token"))).thenReturn(List.of(new double[]{1, 0, 0}));
            when(model.embed(List.of("isolated schedule contents"))).thenReturn(List.of(new double[]{1, 0, 0}));
            when(model.embed(List.of("dimensions do not match"))).thenReturn(List.of(new double[]{1, 0}));
            when(model.embed(List.of("old model coordinates"))).thenReturn(List.of(new double[]{1, 0, 0}));
            var semantic = documents.add("语义命中", "server version token", "", null, null);
            var outOfScope = documents.add("未选资料", "isolated schedule contents", "", null, null);
            var wrongDimensions = documents.add("维度不同", "dimensions do not match", "", null, null);
            when(model.embeddingSpace()).thenReturn("fixture/model-b");
            var wrongSpace = documents.add("旧模型", "old model coordinates", "", null, null);
            when(model.embeddingsEnabled()).thenReturn(false);
            var keyword = documents.add("关键词命中", "read concurrency with bounded locking", "", null, null);
            when(model.embeddingsEnabled()).thenReturn(true);
            when(model.embeddingSpace()).thenReturn("fixture/model-a");

            assertThat(semantic.vectorized()).isTrue();
            assertThat(keyword.vectorized()).isFalse();
            assertThat(jdbc.queryForObject("""
                select cast(pg_typeof(embedding_vector) as text) from knowledge_chunk where document_id = ?
                """, String.class, semantic.id())).isEqualTo("vector");
            assertThat(jdbc.queryForObject("select embedding from knowledge_chunk where document_id = ?",
                String.class, semantic.id())).isNull();

            var result = answers.ask("read concurrency",
                List.of(semantic.id(), keyword.id(), wrongDimensions.id(), wrongSpace.id()), List.of(), null);
            assertThat(result.sources()).extracting(KnowledgeAnswers.Source::documentId)
                .containsExactly(keyword.id(), semantic.id()).doesNotContain(outOfScope.id());
            assertThat(result.sources().getLast().text()).isEqualTo("server version token");
            assertThat(result.answer()).contains("[1]");

            when(model.embeddingSpace()).thenReturn("fixture/model-b");
            when(model.embed(List.of("server version token"))).thenReturn(List.of(new double[]{0, 1}));
            documents.reindex(semantic.id());
            assertThat(jdbc.queryForObject("select vector_dims(embedding_vector) from knowledge_chunk where document_id = ?",
                Integer.class, semantic.id())).isEqualTo(2);
            assertThat(jdbc.queryForObject("select embedding_space from knowledge_chunk where document_id = ?",
                String.class, semantic.id())).isEqualTo("fixture/model-b");
            documents.remove(semantic.id());
            assertThat(jdbc.queryForObject("select count(*) from knowledge_chunk where document_id = ?",
                Integer.class, semantic.id())).isZero();
        } finally {
            admin.execute("drop schema " + schema + " cascade");
        }
    }
}