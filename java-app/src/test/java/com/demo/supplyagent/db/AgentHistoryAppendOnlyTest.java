package com.demo.supplyagent.db;

import com.demo.supplyagent.mapper.AgentHistoryMapper;
import com.demo.supplyagent.support.TestDbInitializer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import javax.sql.DataSource;
import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * W4-3 第 9 项 · agent_history 只追加(不允许 UPDATE/DELETE)的验证演练。
 *
 * 三层证据:
 *  1) 静态:生产源码(src/main)与生产迁移 SQL(根 db/*.sql)中不出现对 agent_history 的行级
 *     写语句(UPDATE/DELETE/TRUNCATE);且所有使用 AgentHistoryMapper 的生产代码,对该 mapper
 *     的调用只落在 insert/select/count 形态,接口自身也不声明任何写/删方法。
 *  2) 运行时观测:创建 → 幂等回放(零写入) → 确认 → 重复确认被拒 全程,早先已存在的审计行逐字段
 *     原样保留(旧行不被就地改写,行数只增);被拒回合行数与内容都不变。
 *  3) 拒绝路径:viewer 携带完全合法的写工具请求体(create-purchase-order / confirm-po)被 403 后,
 *     purchase_po / po_confirmation / agent_history / idempotency_record 四表行数不变(第 10 项补充)。
 *
 * 诚实边界(这不是数据库级约束):
 *  - 未加触发器、未做只读授权:测试夹具清理与 SchemaSeedSmokeTest 的种子行数断言依赖对审计表的
 *    精确 DELETE;dev/test 账号当前为 ALL PRIVILEGES(见 db/00_create_database.sql)。
 *  - 因此本类证明的是「应用层不存在 UPDATE/DELETE 调用路径,且行为观测只追加」;直连 SQL 的
 *    越权修改不在防线内。取舍记录见 docs/07。
 */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class AgentHistoryAppendOnlyTest {

    private static final String LOGIN_URI = "/api/auth/login";
    private static final String CREATE_URI = "/api/agent/tools/create-purchase-order";
    private static final String CONFIRM_URI = "/api/agent/tools/confirm-po";
    private static final String KEY_HEADER = "Idempotency-Key";

    private static final Pattern HISTORY_TABLE = Pattern.compile("agent_history", Pattern.CASE_INSENSITIVE);
    private static final Pattern ROW_WRITE_KEYWORD = Pattern.compile("\\b(update|delete|truncate)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern MAPPER_VAR = Pattern.compile("AgentHistoryMapper\\s+(\\w+)");
    private static final String[] SCANNED_EXTENSIONS = {"java", "xml", "sql", "yml", "yaml", "properties"};

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbc;

    private final Set<String> createdKeys = new LinkedHashSet<>();

    @BeforeAll
    static void initDb(@Autowired DataSource dataSource) throws Exception {
        TestDbInitializer.init(dataSource);
    }

    @BeforeEach
    void independentProductWithoutAnExistingIntent() {
        jdbc.update("INSERT INTO product (sku_code,name,category,pkg_qty,shelf_status,safety_stock,lead_days,created_at,updated_at) "
                + "SELECT 'TST-AUDIT-AUD-001',name,category,pkg_qty,shelf_status,safety_stock,lead_days,created_at,updated_at "
                + "FROM product WHERE sku_code='AUD-001'");
    }

    @AfterEach
    void cleanupCreatedRows() {
        for (String key : createdKeys) {
            jdbc.update("DELETE FROM idempotency_record WHERE idempotency_key = ?", key);
            jdbc.update("DELETE FROM po_confirmation WHERE idempotency_key = ?", key);
        }
        createdKeys.clear();
        String todayPrefix = "PO" + LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE) + "%";
        jdbc.update("DELETE FROM agent_history WHERE task_key LIKE ?", todayPrefix);
        jdbc.update("DELETE FROM po_confirmation WHERE po_no LIKE ?", todayPrefix);
        jdbc.update("DELETE FROM purchase_po WHERE po_no LIKE ?", todayPrefix);
        jdbc.update("DELETE FROM po_no_seq WHERE seq_date = ?", LocalDate.now());
        jdbc.update("DELETE FROM product WHERE sku_code='TST-AUDIT-AUD-001'");
    }

    // ---------- 1. 静态:生产源码与迁移 SQL 无行级写语句 ----------

    @Test
    void productionCodeContainsNoRowWriteStatementsOnHistory() throws IOException {
        List<Path> files = productionFiles();
        assertThat(files.stream().filter(f -> f.toString().endsWith(".java")).count())
                .as("扫描面含生产 Java 源码").isGreaterThanOrEqualTo(10);
        assertThat(files.stream().filter(f -> f.toString().endsWith(".sql")).count())
                .as("扫描面含生产迁移 SQL").isGreaterThanOrEqualTo(6);

        List<String> violations = new ArrayList<>();
        for (Path file : files) {
            List<String> lines = stripComments(Files.readString(file, StandardCharsets.UTF_8)).lines().toList();
            for (int i = 0; i < lines.size(); i++) {
                if (HISTORY_TABLE.matcher(lines.get(i)).find()
                        && ROW_WRITE_KEYWORD.matcher(lines.get(i)).find()) {
                    violations.add(file + ":" + (i + 1) + " -> " + lines.get(i).trim());
                }
            }
        }
        assertThat(violations)
                .as("生产源码/迁移 SQL 中出现对 agent_history 的 UPDATE/DELETE/TRUNCATE")
                .isEmpty();
    }

    // ---------- 2. Mapper 形状与调用站:无写/删路径 ----------

    @Test
    void mapperAndItsConsumersOfferNoWriteOrDeleteCallPath() throws IOException {
        List<String> declared = Arrays.stream(AgentHistoryMapper.class.getDeclaredMethods())
                .filter(m -> !m.isSynthetic() && !m.isBridge())
                .map(Method::getName)
                .sorted()
                .toList();
        assertThat(declared).contains("countHistory", "selectHistoryPage");
        assertThat(declared).allMatch(name -> name.matches("(?i)(select|count)\\w*"));

        // 调用站扫描:取每个文件里该 mapper 的变量名,只允许 insert/select*/count* 形态的调用
        Set<String> calledMethods = new TreeSet<>();
        for (Path file : productionFiles()) {
            if (!file.toString().endsWith(".java")) {
                continue;
            }
            String content = stripComments(Files.readString(file, StandardCharsets.UTF_8));
            Matcher variables = MAPPER_VAR.matcher(content);
            while (variables.find()) {
                Matcher call = Pattern.compile("\\b" + Pattern.quote(variables.group(1)) + "\\s*\\.\\s*(\\w+)\\s*\\(")
                        .matcher(content);
                while (call.find()) {
                    calledMethods.add(call.group(1));
                }
            }
        }
        assertThat(calledMethods).as("扫描非空:能看到既有代表调用").contains("insert", "countHistory", "selectHistoryPage");
        assertThat(calledMethods).allMatch(name -> name.matches("(?i)(insert|select|count)\\w*"));
    }

    // ---------- 3. 运行时:旧行不被改写,只有追加 ----------

    @Test
    void existingHistoryRowsAreNeverRewrittenAcrossCreateReplayConfirmAndRejection() throws Exception {
        String token = token("admin", "admin123");
        String key = register("it-w4h-append");
        String poNo = createDraft(token, key, 48);

        List<Map<String, Object>> afterCreate = historyRows(poNo);
        assertThat(afterCreate).hasSize(1);
        assertThat(afterCreate.get(0)).containsEntry("action_type", "po_create");

        // 幂等回放:同键同参零写入,审计行逐字段不变
        mvc.perform(createRequest(token, key, 48))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.duplicated").value(true));
        assertThat(historyRows(poNo)).isEqualTo(afterCreate);

        // 确认:只追加一行 po_confirm,旧行保持原值
        mvc.perform(confirmRequest(token, poNo))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("confirmed"));
        List<Map<String, Object>> afterConfirm = historyRows(poNo);
        assertThat(afterConfirm).hasSize(2);
        assertThat(afterConfirm.subList(0, afterCreate.size())).isEqualTo(afterCreate);
        assertThat(afterConfirm.get(1)).containsEntry("action_type", "po_confirm");

        // 重复确认被拒(409):不追加、不改写
        mvc.perform(confirmRequest(token, poNo))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(409));
        assertThat(historyRows(poNo)).isEqualTo(afterConfirm);
    }

    // ---------- 4. 拒绝路径:合法体的 viewer 写尝试 403 且四表零变化 ----------

    @Test
    void viewerValidBodyWriteAttemptsAre403WithZeroRowChanges() throws Exception {
        String viewerToken = token("viewer", "viewer123");
        String key = register("it-w4h-viewer-valid");
        long poBefore = countRows("SELECT COUNT(*) FROM purchase_po");
        long confBefore = countRows("SELECT COUNT(*) FROM po_confirmation");
        long historyBefore = countRows("SELECT COUNT(*) FROM agent_history");
        long registryBefore = countRows("SELECT COUNT(*) FROM idempotency_record");

        // 请求体与幂等键完全合法,唯一拒绝理由是角色(403 先于 Controller 校验与任何写库)
        mvc.perform(post(CREATE_URI)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + viewerToken)
                        .header(KEY_HEADER, key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(itemsJson(48)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(403))
                .andExpect(jsonPath("$.message").value("无权限执行该操作"));

        // 合法单号形态:viewer 得到 403 而非 404/400,证明 ACL 先于存在性判定
        mvc.perform(post(CONFIRM_URI)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + viewerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"poNo\":\"PO20990101001\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(403));

        assertThat(countRows("SELECT COUNT(*) FROM purchase_po")).isEqualTo(poBefore);
        assertThat(countRows("SELECT COUNT(*) FROM po_confirmation")).isEqualTo(confBefore);
        assertThat(countRows("SELECT COUNT(*) FROM agent_history")).isEqualTo(historyBefore);
        assertThat(countRows("SELECT COUNT(*) FROM idempotency_record")).isEqualTo(registryBefore);
    }

    // ---------- helpers ----------

    private List<Map<String, Object>> historyRows(String taskKey) {
        return jdbc.queryForList(
                "SELECT id, action_type, who, operator, task_key, before_json, after_json, stats_json, "
                        + "request_id, conversation_id, tool_call_id, executed_at "
                        + "FROM agent_history WHERE task_key = ? ORDER BY id", taskKey);
    }

    private List<Path> productionFiles() throws IOException {
        Path moduleDir = locateModuleDir();
        Path dbDir = locateDbDir(moduleDir);
        List<Path> files = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(moduleDir.resolve("src/main"))) {
            walk.filter(Files::isRegularFile).filter(this::isScanned).forEach(files::add);
        }
        try (Stream<Path> walk = Files.walk(dbDir)) {
            walk.filter(Files::isRegularFile).filter(this::isScanned).forEach(files::add);
        }
        return files;
    }

    private boolean isScanned(Path file) {
        String name = file.getFileName().toString();
        int dot = name.lastIndexOf('.');
        if (dot < 0) {
            return false;
        }
        String extension = name.substring(dot + 1).toLowerCase();
        return Arrays.asList(SCANNED_EXTENSIONS).contains(extension);
    }

    /** surefire 工作目录 = 模块目录(java-app);兜底 IDE 以项目根为 cwd 的情形。 */
    private Path locateModuleDir() {
        for (Path candidate : List.of(Path.of(""), Path.of("java-app"))) {
            if (Files.isDirectory(candidate.resolve("src/main"))) {
                return candidate;
            }
        }
        throw new IllegalStateException("找不到 java-app 模块的 src/main(请在 java-app 下或项目根运行测试)");
    }

    private Path locateDbDir(Path moduleDir) {
        for (Path candidate : List.of(
                moduleDir.resolve("..").resolve("db").normalize(),
                moduleDir.resolveSibling("db"),
                Path.of("db"))) {
            if (Files.isDirectory(candidate)) {
                return candidate;
            }
        }
        throw new IllegalStateException(
                "找不到生产迁移 SQL 目录 db/(cwd=" + Path.of("").toAbsolutePath() + ")");
    }

    /** 先剥注释再按行判关键字:注释里出现 DELETE/agent_history 不算违规。 */
    private static String stripComments(String content) {
        return content
                .replaceAll("(?s)/\\*.*?\\*/", " ")
                .replaceAll("(?m)//[^\\n]*", " ")
                .replaceAll("(?m)--[^\\n]*", " ")
                .replaceAll("(?m)^\\s*#[^\\n]*", " ");
    }

    private String token(String username, String password) throws Exception {
        MvcResult result = mvc.perform(post(LOGIN_URI)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("username", username, "password", password))))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString())
                .path("data").path("token").asText();
    }

    private String createDraft(String token, String key, int qty) throws Exception {
        MvcResult result = mvc.perform(createRequest(token, key, qty))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode data = objectMapper.readTree(result.getResponse().getContentAsString()).path("data");
        return data.at("/items/0/poNo").asText();
    }

    private MockHttpServletRequestBuilder createRequest(String token, String key, int qty) throws Exception {
        return post(CREATE_URI)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .header(KEY_HEADER, key)
                .contentType(MediaType.APPLICATION_JSON)
                .content(itemsJson(qty));
    }

    private MockHttpServletRequestBuilder confirmRequest(String token, String poNo) throws Exception {
        return post(CONFIRM_URI)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("poNo", poNo)));
    }

    private String itemsJson(int qty) throws Exception {
        return objectMapper.writeValueAsString(
                Map.of("items", List.of(Map.of("sku", "TST-AUDIT-AUD-001", "qty", qty))));
    }

    private long countRows(String sql, Object... args) {
        Long count = jdbc.queryForObject(sql, Long.class, args);
        return count == null ? 0 : count;
    }

    private String register(String key) {
        createdKeys.add(key);
        return key;
    }
}
