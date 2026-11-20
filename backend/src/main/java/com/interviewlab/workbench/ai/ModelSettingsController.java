package com.interviewlab.workbench.ai;

import jakarta.annotation.PostConstruct;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.ByteBuffer;
import java.util.Set;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/settings")
@DependsOnDatabaseInitialization
public class ModelSettingsController {
    private final ModelGateway gateway;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final Path keyFile;
    private final SecureRandom random = new SecureRandom();

    public ModelSettingsController(ModelGateway gateway, JdbcTemplate jdbc, TransactionTemplate transaction,
                                   @Value("${workbench.model.key-file:./data/model-store.key}") String keyPath) {
        this.gateway = gateway;
        this.jdbc = jdbc;
        this.transaction = transaction;
        this.keyFile = Path.of(keyPath).toAbsolutePath().normalize();
    }

    @PostConstruct
    void load() {
        List<ModelGateway.Configuration> entries = jdbc.query("""
            select base_url, chat_model, embedding_model, encrypted_key from model_settings where id = 1
            """, (rs, row) -> new ModelGateway.Configuration(rs.getString(1), rs.getString(2), rs.getString(3), decrypt(rs.getString(4))));
        if (!entries.isEmpty()) {
            gateway.apply(entries.getFirst());
        }
        gateway.setProviderLookup(id -> provider(id).configuration());
    }

    @GetMapping("/model")
    Settings settings() {
        var active = gateway.configuration();
        return new Settings(active.base(), active.chat(), active.embedding(), !active.key().isEmpty(), active.enabled() ? "model" : "demo");
    }

    @PutMapping("/model")
    synchronized Settings save(@Valid @RequestBody ModelRequest request) {
        var next = validated(request.baseUrl(), request.chatModel(), request.embeddingModel(),
            request.apiKey(), gateway.configuration(), true);
        transaction.executeWithoutResult(state -> {
            jdbc.update("update model_provider set active_flag = false where active_flag = true");
            persistCurrent(next);
        });
        gateway.apply(next);
        return settings();
    }

    @PostMapping("/model/test")
    Map<String, String> test() {
        return testConfiguration(gateway.configuration());
    }

    @GetMapping("/models")
    List<ProviderInfo> providers() {
        return jdbc.query("""
            select id, display_name, base_url, chat_model, embedding_model, encrypted_key, active_flag
            from model_provider order by active_flag desc, created_at, id
            """, (rs, row) -> new ProviderInfo(rs.getString(1), rs.getString(2), rs.getString(3),
                rs.getString(4), rs.getString(5), !rs.getString(6).isEmpty(), rs.getBoolean(7)));
    }

    @PostMapping("/models")
    @ResponseStatus(HttpStatus.CREATED)
    synchronized ProviderInfo createProvider(@Valid @RequestBody ProviderRequest request) {
        var next = validated(request.baseUrl(), request.chatModel(), request.embeddingModel(), request.apiKey(), empty(), false);
        String id = UUID.randomUUID().toString();
        jdbc.update("""
            insert into model_provider(id, display_name, base_url, chat_model, embedding_model, encrypted_key, active_flag)
            values (?, ?, ?, ?, ?, ?, false)
            """, id, request.name().strip(), next.base(), next.chat(), next.embedding(), encode(next));
        return provider(id).info();
    }

    @PutMapping("/models/{id}")
    synchronized ProviderInfo updateProvider(@PathVariable String id, @Valid @RequestBody ProviderRequest request) {
        Provider stored = provider(id);
        var next = validated(request.baseUrl(), request.chatModel(), request.embeddingModel(), request.apiKey(),
            stored.configuration(), false);
        transaction.executeWithoutResult(state -> {
            jdbc.update("""
                update model_provider set display_name=?, base_url=?, chat_model=?, embedding_model=?, encrypted_key=?
                where id=?
                """, request.name().strip(), next.base(), next.chat(), next.embedding(), encode(next), id);
            if (stored.active()) persistCurrent(next);
        });
        if (stored.active()) gateway.apply(next);
        return provider(id).info();
    }

    @PostMapping("/models/{id}/activate")
    synchronized ProviderInfo activate(@PathVariable String id) {
        Provider selected = provider(id);
        if (!selected.configuration().enabled()) {
            throw bad("无法读取密钥，请重新填写后再切换。");
        }
        transaction.executeWithoutResult(state -> {
            jdbc.update("update model_provider set active_flag = false where active_flag = true");
            jdbc.update("update model_provider set active_flag = true where id=?", id);
            persistCurrent(selected.configuration());
        });
        gateway.apply(selected.configuration());
        return provider(id).info();
    }

    @PostMapping("/models/{id}/test")
    Map<String, String> testProvider(@PathVariable String id) {
        return testConfiguration(provider(id).configuration());
    }

    @DeleteMapping("/models/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    synchronized void deleteProvider(@PathVariable String id) {
        Provider stored = provider(id);
        transaction.executeWithoutResult(state -> {
            jdbc.update("delete from model_provider where id=?", id);
            if (stored.active()) persistCurrent(empty());
        });
        if (stored.active()) gateway.apply(empty());
    }

    private Provider provider(String id) {
        return jdbc.query("""
            select id, display_name, base_url, chat_model, embedding_model, encrypted_key, active_flag
            from model_provider where id=?
            """, (rs, row) -> new Provider(rs.getString(1), rs.getString(2),
                new ModelGateway.Configuration(rs.getString(3), rs.getString(4), rs.getString(5), decrypt(rs.getString(6))),
                rs.getBoolean(7)), id).stream().findFirst()
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "模型配置不存在。"));
    }

    private void persistCurrent(ModelGateway.Configuration config) {
        jdbc.update("""
            merge into model_settings(id, base_url, chat_model, embedding_model, encrypted_key)
            key(id) values (1, ?, ?, ?, ?)
            """, config.base(), config.chat(), config.embedding(), encode(config));
    }

    private String encode(ModelGateway.Configuration config) {
        return config.key().isEmpty() ? "" : encrypt(config.key());
    }

    private Map<String, String> testConfiguration(ModelGateway.Configuration config) {
        gateway.verify(config);
        return Map.of("message", "已连接模型服务。");
    }

    private ModelGateway.Configuration empty() {
        return new ModelGateway.Configuration("", "", "", "");
    }

    private ModelGateway.Configuration validated(String endpoint, String chatModel, String embedModel, String suppliedKey,
                                                  ModelGateway.Configuration previous, boolean allowEmpty) {
        String base = ModelGateway.cleanBase(endpoint);
        String chat = chatModel.strip();
        String embed = embedModel.strip();
        String key = suppliedKey.strip();
        if (allowEmpty && base.isEmpty() && chat.isEmpty() && embed.isEmpty() && key.isEmpty()) {
            return empty();
        }
        if (base.isEmpty() || chat.isEmpty()) throw bad("请填写模型地址和聊天模型。");
        try {
            ModelGateway.endpoint(base, "/chat/completions");
        } catch (IllegalArgumentException ex) {
            throw bad("请输入 OpenAI 兼容基址。远程服务请使用 HTTPS。");
        }
        if (key.isEmpty() && previous.base().equals(base)) key = previous.key();
        if (key.isEmpty()) throw bad("请为当前模型地址输入 API Key。");
        if (!key.matches("[!-~]+")) throw bad("API Key 需要使用可打印的 ASCII 字符。");
        return new ModelGateway.Configuration(base, chat, embed, key);
    }

    private byte[] secretKey(boolean create) throws IOException {
        if (create && Files.notExists(keyFile)) {
            Files.createDirectories(keyFile.getParent());
            byte[] bytes = new byte[32];
            random.nextBytes(bytes);
            if (keyFile.getFileSystem().supportedFileAttributeViews().contains("posix")) {
                var permissions = PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"));
                try (var channel = Files.newByteChannel(keyFile,
                    Set.of(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE), permissions)) {
                    var data = ByteBuffer.wrap(bytes);
                    while (data.hasRemaining()) channel.write(data);
                }
            } else {
                Files.write(keyFile, bytes, StandardOpenOption.CREATE_NEW);
            }
        }
        return Files.readAllBytes(keyFile);
    }

    private String encrypt(String value) {
        try {
            byte[] nonce = new byte[12];
            random.nextBytes(nonce);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(secretKey(true), "AES"), new GCMParameterSpec(128, nonce));
            byte[] ciphertext = cipher.doFinal(value.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(nonce) + "." + Base64.getEncoder().encodeToString(ciphertext);
        } catch (IOException | GeneralSecurityException ex) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "无法保存模型凭据。", ex);
        }
    }

    private String decrypt(String value) {
        if (value.isEmpty()) {
            return "";
        }
        try {
            String[] parts = value.split("\\.", 2);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(secretKey(false), "AES"),
                new GCMParameterSpec(128, Base64.getDecoder().decode(parts[0])));
            return new String(cipher.doFinal(Base64.getDecoder().decode(parts[1])), StandardCharsets.UTF_8);
        } catch (IOException | GeneralSecurityException | IllegalArgumentException | IndexOutOfBoundsException ex) {
            return "";
        }
    }

    private ResponseStatusException bad(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }

    record ModelRequest(@NotNull @Size(max = 280) String baseUrl, @NotNull @Size(max = 100) String chatModel,
                        @NotNull @Size(max = 100) String embeddingModel, @NotNull @Size(max = 512) String apiKey) {}
    record ProviderRequest(@NotBlank @Size(max = 80) String name, @NotNull @Size(max = 280) String baseUrl,
                           @NotNull @Size(max = 100) String chatModel, @NotNull @Size(max = 100) String embeddingModel,
                           @NotNull @Size(max = 512) String apiKey) {}
    record ProviderInfo(String id, String name, String baseUrl, String chatModel, String embeddingModel, boolean hasKey, boolean active) {}
    record Provider(String id, String name, ModelGateway.Configuration configuration, boolean active) {
        ProviderInfo info() {
            return new ProviderInfo(id, name, configuration.base(), configuration.chat(), configuration.embedding(),
                !configuration.key().isEmpty(), active);
        }
    }
    record Settings(String baseUrl, String chatModel, String embeddingModel, boolean hasKey, String mode) {}
}
