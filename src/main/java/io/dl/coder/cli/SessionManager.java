package io.dl.coder.cli;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 会话注册表：记录用户创建过的 session 名，持久化在 ~/.dl-coder/sessions.json。
 *
 * <p>会话内容本身由框架的 AgentStateStore 按 (userId, sessionId) 自动持久化/恢复，
 * 这里只做名字清单（供 /session list 与切换），不存对话数据。
 */
public class SessionManager {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);

    /** 默认会话名 */
    public static final String DEFAULT_SESSION = "default";

    private final Path registryFile;
    /** session 名 → 创建时间（保持创建顺序） */
    private final Map<String, String> sessions = new LinkedHashMap<>();

    public SessionManager(Path registryFile) {
        this.registryFile = registryFile;
        load();
    }

    public static SessionManager createDefault() {
        return new SessionManager(Path.of(System.getProperty("user.home"),
                AppConfigDir.CONFIG_DIR, "sessions.json"));
    }

    /** 注册会话（已存在则忽略），落盘。 */
    public void register(String name) {
        if (sessions.containsKey(name)) {
            return;
        }
        sessions.put(name, LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")));
        save();
    }

    public boolean exists(String name) {
        return sessions.containsKey(name);
    }

    public List<String> names() {
        return new ArrayList<>(sessions.keySet());
    }

    public String createdAt(String name) {
        return sessions.get(name);
    }

    private void load() {
        if (!Files.isRegularFile(registryFile)) {
            return;
        }
        try {
            RegistryDto dto = MAPPER.readValue(Files.readString(registryFile), RegistryDto.class);
            if (dto.sessions != null) {
                dto.sessions.forEach(s -> sessions.put(s.name, s.createdAt));
            }
        } catch (IOException e) {
            // 注册表损坏不致命：忽略，重新开始记录
            System.err.println("警告: 会话注册表解析失败，将重建: " + e.getMessage());
        }
    }

    private void save() {
        try {
            Files.createDirectories(registryFile.getParent());
            RegistryDto dto = new RegistryDto();
            sessions.forEach((name, createdAt) -> {
                EntryDto e = new EntryDto();
                e.name = name;
                e.createdAt = createdAt;
                dto.sessions.add(e);
            });
            MAPPER.writeValue(registryFile.toFile(), dto);
        } catch (IOException e) {
            System.err.println("警告: 会话注册表写入失败: " + e.getMessage());
        }
    }

    /** 避免与 config 包互相 import 常量 */
    private static final class AppConfigDir {
        static final String CONFIG_DIR = ".dl-coder";
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private static final class RegistryDto {
        List<EntryDto> sessions = new ArrayList<>();
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private static final class EntryDto {
        String name;
        String createdAt;
    }
}
