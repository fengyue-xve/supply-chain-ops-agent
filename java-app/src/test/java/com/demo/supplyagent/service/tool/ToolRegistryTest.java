package com.demo.supplyagent.service.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * W1-D5 / W3-③ 工具目录与契约快照一致性（纯 unit，不依赖 Spring/DB）。
 * 路径约定：测试工作目录 = java-app/，仓库根在上一级（同 TestDbInitializer 的 ../db 写法）。
 */
class ToolRegistryTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Path REPO_ROOT = Path.of("..");

    private final ToolRegistry registry = new ToolRegistry();

    @Test
    void registersExactlyFiveToolsInStableOrder() {
        assertThat(registry.list()).hasSize(5);
        assertThat(registry.list().stream().map(ToolDefinition::getName))
                .containsExactly("query-stock-level", "query-sales", "suggest-replenish",
                        "create-purchase-order", "confirm-po");
    }

    @Test
    void toolNamesAreUnique() {
        List<String> names = registry.list().stream().map(ToolDefinition::getName).toList();
        assertThat(names).doesNotHaveDuplicates();
    }

    @Test
    void readOnlyFlagSeparatesReadToolsFromWriteTools() {
        assertThat(registry.list().stream().filter(ToolDefinition::isReadOnly).map(ToolDefinition::getName))
                .containsExactly("query-stock-level", "query-sales", "suggest-replenish");
        assertThat(registry.list().stream().filter(t -> !t.isReadOnly()).map(ToolDefinition::getName))
                .containsExactly("create-purchase-order", "confirm-po");
    }

    @Test
    void onlyCreatePurchaseOrderRequiresApproval() {
        assertThat(registry.list().stream().filter(ToolDefinition::isRequiresApproval).map(ToolDefinition::getName))
                .containsExactly("create-purchase-order");
    }

    @Test
    void timeoutsMatchReadAndWriteProfiles() {
        for (ToolDefinition t : registry.list()) {
            long expected = t.isReadOnly() ? 3_000L : 5_000L;
            assertThat(t.getTimeoutMs()).as(t.getName()).isEqualTo(expected);
        }
    }

    @Test
    void everyToolHasVersionOne() {
        assertThat(registry.list()).allMatch(t -> "1".equals(t.getVersion()));
    }

    @Test
    @SuppressWarnings("unchecked")
    void everyToolCarriesInputAndOutputSchema() {
        for (ToolDefinition t : registry.list()) {
            assertThat(t.getInputSchema()).as(t.getName()).isNotEmpty().containsKeys("type", "properties");
            assertThat(t.getOutputSchema()).as(t.getName()).isNotEmpty().containsKeys("type", "properties");
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void readOnlyToolsDeclareItemsMissingSkusTotalOutput() {
        for (ToolDefinition t : registry.list().subList(0, 3)) {
            java.util.Map<String, Object> props =
                    (java.util.Map<String, Object>) t.getOutputSchema().get("properties");
            assertThat(props).as(t.getName()).containsKeys("items", "missingSkus", "total");
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void createToolDeclaresItemsInputAndDuplicatedOutput() {
        ToolDefinition create = byName("create-purchase-order");
        assertThat((List<String>) create.getInputSchema().get("required")).containsExactly("items");
        java.util.Map<String, Object> props =
                (java.util.Map<String, Object>) create.getOutputSchema().get("properties");
        assertThat(props).containsKeys("items", "total", "duplicated");
        assertThat((List<String>) create.getOutputSchema().get("required"))
                .containsExactly("items", "total", "duplicated");
    }

    @Test
    @SuppressWarnings("unchecked")
    void confirmToolDeclaresPoNoInputAndStatusOutput() {
        ToolDefinition confirm = byName("confirm-po");
        assertThat((List<String>) confirm.getInputSchema().get("required")).containsExactly("poNo");
        java.util.Map<String, Object> props =
                (java.util.Map<String, Object>) confirm.getOutputSchema().get("properties");
        assertThat(props).containsKeys("poNo", "sku", "qty", "status");
    }

    private ToolDefinition byName(String name) {
        return registry.list().stream().filter(t -> t.getName().equals(name)).findFirst().orElseThrow();
    }

    @Test
    void contractMatchesSnapshotFile() throws Exception {
        JsonNode fileNode = MAPPER.readTree(Files.readString(snapshotFile()));
        JsonNode exportedNode = MAPPER.valueToTree(registry.contractData());
        assertThat(exportedNode).isEqualTo(fileNode);
    }

    @Test
    void snapshotFileHasNoGeneratedAt() throws Exception {
        String text = Files.readString(snapshotFile());
        assertThat(text).doesNotContain("generatedAt");
    }

    @Test
    void docsNoLongerUseSnakeCaseToolNames() throws Exception {
        String doc = Files.readString(REPO_ROOT.resolve("docs/01-v0.1架构设计.md"));
        assertThat(doc)
                .doesNotContain("query_stock_level")
                .doesNotContain("query_sales")
                .doesNotContain("suggest_replenish")
                .doesNotContain("confirm_po")
                .doesNotContain("create_purchase_order")
                .doesNotContain("query_history");
    }

    private Path snapshotFile() {
        return REPO_ROOT.resolve("contracts/tools-schema.json");
    }
}
