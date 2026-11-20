package com.interviewlab.workbench.knowledge;

import com.interviewlab.workbench.ai.ModelGateway;
import com.interviewlab.workbench.knowledge.PassageSearch.Passage;
import com.interviewlab.workbench.resume.ResumeFiles;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import javax.xml.parsers.ParserConfigurationException;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import org.xml.sax.SAXException;

@Service
class KnowledgeDocuments {
    private static final String DOCUMENTS = """
        select d.id, d.title, d.category, d.filename, d.created_at, count(c.chunk_index) as chunks,
          count(c.%s) as vectors, d.size_bytes, d.question_count, d.access_count
        from knowledge_document d join knowledge_chunk c on c.document_id = d.id
        """;
    private static final String GROUP_DOCUMENTS = """
         group by d.id, d.title, d.category, d.filename, d.created_at, d.size_bytes, d.question_count, d.access_count
        """;
    private final JdbcTemplate jdbc;
    private final NamedParameterJdbcTemplate named;
    private final TransactionTemplate transaction;
    private final KnowledgeVectorStore vectorStore;
    private final String documentQuery;
    private final ModelGateway model;

    KnowledgeDocuments(JdbcTemplate jdbc, NamedParameterJdbcTemplate named, TransactionTemplate transaction,
                       KnowledgeVectorStore vectorStore, ModelGateway model) {
        this.jdbc = jdbc;
        this.named = named;
        this.transaction = transaction;
        this.vectorStore = vectorStore;
        this.documentQuery = DOCUMENTS.formatted(vectorStore.column());
        this.model = model;
    }

    List<Document> list() {
        return jdbc.query(documentQuery + GROUP_DOCUMENTS + " order by d.created_at desc, d.id", this::row);
    }

    Document get(String id) {
        return jdbc.query(documentQuery + " where d.id = ?" + GROUP_DOCUMENTS, this::row, id)
            .stream().findFirst().orElseThrow(KnowledgeDocuments::missing);
    }

    private Document row(ResultSet result, int row) throws SQLException {
        return new Document(result.getString("id"), result.getString("title"), result.getInt("chunks"),
            result.getString("category"), result.getString("filename"), result.getInt("vectors") > 0,
            result.getObject("created_at", OffsetDateTime.class), result.getObject("size_bytes", Long.class),
            result.getLong("question_count"), result.getLong("access_count"));
    }

    Document add(String title, String text, String category, String filename, byte[] original) {
        title = clean(title);
        text = clean(text);
        category = clean(category);
        if (title.isEmpty() || title.length() > 100 || category.length() > 100) {
            throw bad("请输入 1 至 100 字符的文档标题，分类最多 100 字符。");
        }
        if (text.isEmpty() || text.length() > 60_000 || text.indexOf(0) >= 0) {
            throw bad("请提供 1 至 60000 字符的可读正文。扫描件可先提取文字。");
        }
        List<String> chunks = PassageSearch.split(text);
        var vectors = embed(chunks);
        String id = UUID.randomUUID().toString();
        String documentTitle = title;
        String documentText = text;
        String documentCategory = category;
        long size = original == null ? text.getBytes(StandardCharsets.UTF_8).length : original.length;
        transaction.executeWithoutResult(status -> {
            jdbc.update("""
                insert into knowledge_document(id, title, category, filename, original_file, original_text, size_bytes)
                values (?, ?, ?, ?, ?, ?, ?)
                """, id, documentTitle, documentCategory, filename, original, documentText, size);
            for (int index = 0; index < chunks.size(); index++) {
                jdbc.update("""
                    insert into knowledge_chunk(document_id, chunk_index, content, %s, embedding_space)
                    values (?, ?, ?, %s, ?)
                    """.formatted(vectorStore.column(), vectorStore.bindValue()),
                    id, index + 1, chunks.get(index), vectors.at(index), vectors.space());
            }
        });
        return get(id);
    }

    Document upload(MultipartFile file, String title, String category) {
        String filename = clean(file.getOriginalFilename()).replace('\\', '/');
        filename = filename.substring(filename.lastIndexOf('/') + 1).replaceAll("[\\p{Cntrl}]", "");
        int extensionAt = filename.lastIndexOf('.');
        if (file.isEmpty() || filename.length() > 255 || extensionAt <= 0) {
            throw bad("请选择一个有效的文档文件。");
        }
        String suffix = filename.substring(extensionAt).toLowerCase(Locale.ROOT);
        String fallbackTitle = filename.substring(0, extensionAt);
        if (fallbackTitle.length() > 100) {
            fallbackTitle = fallbackTitle.substring(0, 100);
        }
        try {
            byte[] bytes = file.getBytes();
            String text = switch (suffix) {
                case ".pdf" -> readPdf(bytes);
                case ".docx" -> ResumeFiles.readDocx(bytes);
                case ".txt", ".md" -> StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString().replaceFirst("^\\uFEFF", "");
                default -> throw bad("支持 PDF、DOCX、UTF-8 TXT 和 Markdown 文件。");
            };
            return add(title.isBlank() ? fallbackTitle : title, text, category, filename, bytes);
        } catch (CharacterCodingException ex) {
            throw bad("文本文件需要使用 UTF-8 编码。");
        } catch (IOException | SAXException | ParserConfigurationException ex) {
            throw bad("无法提取文件正文，请检查 PDF、DOCX 或 UTF-8 文本格式。");
        }
    }

    private String readPdf(byte[] bytes) throws IOException {
        try (var pdf = Loader.loadPDF(bytes)) {
            if (pdf.getNumberOfPages() > 100) {
                throw bad("请上传 100 页以内的 PDF。");
            }
            return new PDFTextStripper().getText(pdf);
        }
    }

    Document categorize(String id, String category) {
        if (jdbc.update("update knowledge_document set category = ? where id = ?", clean(category), id) == 0) {
            throw missing();
        }
        return get(id);
    }

    Document reindex(String id) {
        get(id);
        List<Passage> chunks = passages(List.of(id), "");
        var vectors = embed(chunks.stream().map(Passage::text).toList());
        if (vectors.values().isEmpty()) {
            return get(id);
        }
        transaction.executeWithoutResult(status -> {
            for (int i = 0; i < chunks.size(); i++) {
                jdbc.update("""
                    update knowledge_chunk set %s = %s, embedding_space = ? where document_id = ? and chunk_index = ?
                    """.formatted(vectorStore.column(), vectorStore.bindValue()),
                    vectors.at(i), vectors.space(), id, chunks.get(i).chunkIndex());
            }
        });
        return get(id);
    }

    Download download(String id) {
        var file = jdbc.query("""
            select title, filename, original_file, original_text from knowledge_document where id = ?
            """, (rs, row) -> {
                String filename = rs.getString("filename");
                if (filename != null) {
                    return new Download(filename, rs.getBytes("original_file"));
                }
                String title = rs.getString("title").replaceAll("[\\p{Cntrl}/\\\\]", "_");
                String text = rs.getString("original_text");
                return new Download(title + ".txt", text == null ? null : text.getBytes(StandardCharsets.UTF_8));
            }, id).stream().findFirst().orElseThrow(KnowledgeDocuments::missing);
        if (file.content() != null) {
            countAccess(id);
            return file;
        }
        String content = String.join("\n\n", passages(List.of(id), "").stream().map(Passage::text).toList());
        countAccess(id);
        return new Download(file.name(), content.getBytes(StandardCharsets.UTF_8));
    }

    void countAccess(String id) {
        jdbc.update("update knowledge_document set access_count = access_count + 1 where id = ?", id);
    }

    void countQuestions(List<String> scope) {
        if (!scope.isEmpty()) {
            named.update("update knowledge_document set question_count = question_count + 1 where id in (:scope)",
                Map.of("scope", scope));
        }
    }

    void remove(String id) {
        if (jdbc.update("delete from knowledge_document where id = ?", id) == 0) {
            throw missing();
        }
    }

    List<Passage> passages(List<String> scope, String encoding) {
        String filter = scope.isEmpty() ? "" : " where d.id in (:scope)";
        String stored = vectorStore.usesPgvector() || encoding.isEmpty() ? "null" : "c.embedding";
        return named.query("""
            select d.id, d.title, c.chunk_index, c.content, %s as stored_embedding, c.embedding_space
            from knowledge_chunk c join knowledge_document d on c.document_id = d.id
            """.formatted(stored) + filter + " order by d.id, c.chunk_index", Map.of("scope", scope), (rs, row) -> {
                String vector = rs.getString("stored_embedding");
                return new Passage(rs.getString("id"), rs.getString("title"), rs.getInt("chunk_index"),
                    rs.getString("content"), vector != null && encoding.equals(rs.getString("embedding_space"))
                        ? vectorStore.decodeLegacy(vector) : null);
            });
    }

    private EmbeddingBatch embed(List<String> chunks) {
        if (!model.embeddingsEnabled()) {
            return new EmbeddingBatch(null, List.of());
        }
        String encoding = model.embeddingSpace();
        List<String> encoded = model.embed(chunks).stream().map(vectorStore::encode).toList();
        if (!encoding.equals(model.embeddingSpace())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "嵌入模型配置已变更，请重试索引。");
        }
        return new EmbeddingBatch(encoding, encoded);
    }

    private record EmbeddingBatch(String space, List<String> values) {
        String at(int index) {
            return values.isEmpty() ? null : values.get(index);
        }
    }

    static String clean(String value) {
        return value == null ? "" : value.strip();
    }

    static ResponseStatusException bad(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }

    private static ResponseStatusException missing() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "文档不存在。");
    }

    record Document(String id, String title, int chunkCount, String category, String filename, boolean vectorized,
                    OffsetDateTime createdAt, Long sizeBytes, long questionCount, long accessCount) {}
    record Download(String name, byte[] content) {}
}
