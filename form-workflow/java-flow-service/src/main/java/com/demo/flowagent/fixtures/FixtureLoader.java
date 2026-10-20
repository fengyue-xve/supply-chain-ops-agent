package com.demo.flowagent.fixtures;

import com.demo.flowagent.config.FlowProperties;
import com.demo.flowagent.domain.Role;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** 启动时加载 fixtures/（与 Pi 侧共用同一份文件），缺文件/解析失败即启动失败。 */
@Component
public class FixtureLoader {

    private final ObjectMapper mapper;
    private final FlowProperties properties;

    private List<RecordFixture> records = List.of();
    private final Map<String, RuleEntry> rules = new ConcurrentHashMap<>();
    private final Map<String, UserEntry> users = new ConcurrentHashMap<>();

    public FixtureLoader(ObjectMapper mapper, FlowProperties properties) {
        this.mapper = mapper;
        this.properties = properties;
    }

    @PostConstruct
    void load() {
        Path dir = Path.of(properties.fixturesDir());
        RecordsFile recordsFile = read(dir.resolve("records.json"), RecordsFile.class);
        RulesFile rulesFile = read(dir.resolve("rules.json"), RulesFile.class);
        UsersFile usersFile = read(dir.resolve("users.json"), UsersFile.class);
        this.records = List.copyOf(recordsFile.records());
        rulesFile.rules().forEach(rules::put);
        usersFile.users().forEach(users::put);
    }

    private <T> T read(Path path, Class<T> type) {
        try (InputStream in = Files.newInputStream(path)) {
            return mapper.readValue(in, type);
        } catch (IOException e) {
            throw new IllegalStateException("无法加载 fixtures 文件: " + path.toAbsolutePath(), e);
        }
    }

    public List<RecordFixture> records() {
        return records;
    }

    public Map<String, RuleEntry> rules() {
        return rules;
    }

    public UserEntry userByToken(String token) {
        return users.get(token);
    }

    public record RecordsFile(String fixtureVersion, String description, List<RecordFixture> records) {
    }

    public record RecordFixture(String recordId, String flowType, int businessVersion, String skuCode,
                                String markRule, CurrentMark currentMark, FaultFixture demoFault) {
    }

    public record CurrentMark(String value, String ruleSource) {
    }

    public record FaultFixture(String kind, Integer remaining) {
    }

    public record RulesFile(String fixtureVersion, String description, Map<String, RuleEntry> rules) {
    }

    public record RuleEntry(String proposedValue, String ruleSource) {
    }

    public record UsersFile(String fixtureVersion, String description, Map<String, UserEntry> users) {
    }

    public record UserEntry(String actorId, String role) {
        public Role roleEnum() {
            return Role.valueOf(role.toUpperCase());
        }
    }
}
