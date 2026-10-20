package com.demo.supplyagent.service.tool;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 单个工具的不可变定义（8 字段），与 contracts/tools-schema.json 中 tools[] 元素一一对应。
 * toContractMap() 输出即契约 JSON 的序列化视图（LinkedHashMap 保序便于人读）。
 */
@Getter
@AllArgsConstructor
public final class ToolDefinition {

    /** 工具名 = URL 后缀，全链 kebab-case 唯一键 */
    private final String name;

    /** 面向 LLM/FastAPI 的中文说明：做什么、参数语义、边界行为 */
    private final String description;

    /** 只读工具 = 不产生任何写副作用（写工具未来为 false） */
    private final boolean readOnly;

    /** 是否需要人工二次确认后才可执行（写工具未来为 true） */
    private final boolean requiresApproval;

    /** 调用方(编排层)允许的最大执行时长，超出由调用方超时兜底 */
    private final int timeoutMs;

    /** 工具契约版本，变更描述/参数语义时递增 */
    private final String version;

    /** 入参声明：JSON Schema subset(type/properties/required/enum/oneOf/maxItems/default) */
    private final Map<String, Object> inputSchema;

    /** 出参声明：描述统一信封 data 部分的业务结构 */
    private final Map<String, Object> outputSchema;

    public Map<String, Object> toContractMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("name", name);
        map.put("description", description);
        map.put("readOnly", readOnly);
        map.put("requiresApproval", requiresApproval);
        map.put("timeoutMs", timeoutMs);
        map.put("version", version);
        map.put("inputSchema", inputSchema);
        map.put("outputSchema", outputSchema);
        return map;
    }
}
