package com.demo.supplyagent.service.tool;

import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 工具目录（单一事实源，Java 侧）：注册 3 个只读工具 + 2 个敏感写工具的不可变定义。
 * 只做「注册 + 查找 + 契约导出」，不执行工具、不碰 HTTP —— 执行权仍在既有 Controller/Service。
 * 写工具 readOnly=false：工具级 ACL（ToolAccessService）据此自动收敛为仅 admin；
 * FastAPI 侧按 readOnly/requiresApproval 过滤，写工具不进模型可见列表。
 * 契约快照 contracts/tools-schema.json 由此导出，一致性由测试锁死。
 */
@Component
public class ToolRegistry {

    private static final String CONTRACT_VERSION = "1";
    private static final int TOOL_TIMEOUT_MS = 3_000;
    /** 写工具超时放宽（事务 + 幂等回查 + 编号重试），仍由调用方兜底。 */
    private static final int TOOL_WRITE_TIMEOUT_MS = 5_000;

    private final List<ToolDefinition> definitions;

    public ToolRegistry() {
        this.definitions = List.of(
                new ToolDefinition("query-stock-level",
                        "查询一个或多个 SKU（或整个类目）的最新库存快照：当前可用量与档位（out=断货、low=偏低、ok=正常、high=偏高），附档位对应的快照日期。查询不到的 SKU 进 missingSkus，不整单失败。",
                        true, false, TOOL_TIMEOUT_MS, "1",
                        ToolSchemas.QUERY_STOCK_LEVEL_INPUT, ToolSchemas.QUERY_STOCK_LEVEL_OUTPUT),
                new ToolDefinition("query-sales",
                        "查询单个 SKU（或整个类目）在近 7/30 个自然日内的销量：逐日序列（缺销量的日期补 0，连续不跳日）、窗口总量、日均销量（2 位小数），用于估计需求速度。数据为演示库固定业务日窗口。",
                        true, false, TOOL_TIMEOUT_MS, "1",
                        ToolSchemas.QUERY_SALES_INPUT, ToolSchemas.QUERY_SALES_OUTPUT),
                new ToolDefinition("suggest-replenish",
                        "基于现存库存与固定演示日2026-09-03截止的7/30日日均销量，使用商品安全库存、采购提前期和箱规计算整箱补货建议。总覆盖期已含提前期，保护期=max(总覆盖期,提前期)；目标库存=ceil(日均×保护期+安全库存)，缺口=max(0,目标库存-现存)，建议量按箱规向上取整。urgent指现存低于安全库存加提前期需求，箱规不放大紧急度。附库存与销量日期及复核状态；缺失销量不视为0。未计在途或预留，须检查已有采购并人工复核；只读，不建单、不增加库存。",
                        true, false, TOOL_TIMEOUT_MS, "1",
                        ToolSchemas.SUGGEST_REPLENISH_INPUT, ToolSchemas.SUGGEST_REPLENISH_OUTPUT),
                new ToolDefinition("create-purchase-order",
                        "敏感写工具（仅 admin）：为指定 SKU 与数量生成采购单草稿（status=pending，本工具绝不直接生效），待人工用 confirm-po 确认。幂等键经 Idempotency-Key 请求头传入且必填：同键同参数回放首次结果（duplicated=true，零写入），同键异参数 409。qty 必须为正且为包装数量整数倍，否则硬 400 并附向上取整建议值。校验失败/拒绝的请求零写入、不落审计。",
                        false, true, TOOL_WRITE_TIMEOUT_MS, "1",
                        ToolSchemas.CREATE_PURCHASE_ORDER_INPUT, ToolSchemas.CREATE_PURCHASE_ORDER_OUTPUT),
                new ToolDefinition("confirm-po",
                        "敏感写工具（仅 admin）：人工确认一张 pending_confirmation 状态的采购单，确认后置 confirmed 并落审计。重复确认或状态不符 409 并返回当前状态；确认时 SKU 已下架/不存在则置 failed、取消采购单并返回 409；单号不存在 404。确认不需要幂等键：重复请求由状态机条件迁移兜底。",
                        false, false, TOOL_WRITE_TIMEOUT_MS, "1",
                        ToolSchemas.CONFIRM_PO_INPUT, ToolSchemas.CONFIRM_PO_OUTPUT));

        long distinct = definitions.stream().map(ToolDefinition::getName).distinct().count();
        if (distinct != definitions.size()) {
            throw new IllegalStateException("工具名重复，注册表拒绝启动");
        }
    }

    public List<ToolDefinition> list() {
        return definitions;
    }

    /** 按工具名查找(工具名 = URL 后缀)。未注册返回空——调用方决定 404 语义,不在此抛错。 */
    public java.util.Optional<ToolDefinition> findByName(String name) {
        return definitions.stream().filter(d -> d.getName().equals(name)).findFirst();
    }

    /** 发现端点摘要：data = {tools:[name/description/readOnly/requiresApproval/timeoutMs/version], total} */
    public Map<String, Object> summaryData() {
        Map<String, Object> data = new LinkedHashMap<>();
        List<Map<String, Object>> tools = definitions.stream().map(this::summary).toList();
        data.put("tools", tools);
        data.put("total", tools.size());
        return data;
    }

    /** 完整契约：data = {version, tools:[8 字段全量]}，与 contracts/tools-schema.json 语义一致 */
    public Map<String, Object> contractData() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("version", CONTRACT_VERSION);
        data.put("tools", definitions.stream().map(ToolDefinition::toContractMap).toList());
        return data;
    }

    private Map<String, Object> summary(ToolDefinition d) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("name", d.getName());
        map.put("description", d.getDescription());
        map.put("readOnly", d.isReadOnly());
        map.put("requiresApproval", d.isRequiresApproval());
        map.put("timeoutMs", d.getTimeoutMs());
        map.put("version", d.getVersion());
        return map;
    }
}
