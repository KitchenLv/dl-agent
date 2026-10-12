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
import java.security.SecureRandom;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * 会话注册表：记录用户创建过的 session 名，持久化在 ~/.dl-coder/sessions.json。
 *
 * <p>会话内容本身由框架的 AgentStateStore 按 (userId, sessionId) 自动持久化/恢复，
 * 这里只做名字清单（供 /session list 与切换），不存对话数据。
 *
 * <p>随机名（形容词-名词-两位数，如 brave-otter-42）：全小写英文+连字符，直接命中
 * stateStore 的文件名安全模式 ^[a-zA-Z0-9_\-.]+$（中文名会被 Base64 编码成不可读目录）。
 */
public class SessionManager {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);

    private static final String[] ADJECTIVES = {
            "brave", "calm", "clever", "cosmic", "crimson", "curious", "dapper", "eager",
            "fancy", "gentle", "glossy", "happy", "humble", "jolly", "keen", "lively",
            "lucky", "mellow", "mighty", "nimble", "patient", "plucky", "quiet", "rapid",
            "shiny", "silky", "steady", "sunny", "swift", "tidy", "vivid", "witty"
    };

    private static final String[] NOUNS = {
            "badger", "beacon", "bison", "canyon", "cedar", "comet", "copper", "crane",
            "dolphin", "eagle", "ember", "falcon", "ferret", "gecko", "harbor", "heron",
            "ibis", "juniper", "koala", "lantern", "lynx", "marten", "meadow", "otter",
            "panther", "puffin", "raven", "sparrow", "summit", "teal", "walrus", "wombat"
    };

    private static final Random RANDOM = new SecureRandom();

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

    /** 生成不与现有会话重名的随机名：形容词-名词-两位数（如 brave-otter-42）。 */
    public String randomName() {
        while (true) {
            String candidate = ADJECTIVES[RANDOM.nextInt(ADJECTIVES.length)] + "-"
                    + NOUNS[RANDOM.nextInt(NOUNS.length)] + "-"
                    + String.format("%02d", RANDOM.nextInt(100));
            if (!exists(candidate)) {
                return candidate;
            }
        }
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

    // DTO 字段必须可见：Jackson 默认不序列化 private 字段，曾导致 sessions.json 恒为空对象、
    // 跨进程会话清单丢失（P2-1 测试发现）
    @JsonIgnoreProperties(ignoreUnknown = true)
    private static final class RegistryDto {
        public List<EntryDto> sessions = new ArrayList<>();
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private static final class EntryDto {
        public String name;
        public String createdAt;
    }
}
