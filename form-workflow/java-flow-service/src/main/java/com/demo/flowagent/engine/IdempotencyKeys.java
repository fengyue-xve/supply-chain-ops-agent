package com.demo.flowagent.engine;

import com.demo.flowagent.domain.FlowType;

/**
 * 幂等键派生（契约 contracts/README.md）：
 * FLOW_IDK_ + sha256(source_record_id + ":" + flow_type + ":" + business_version)[:16]
 * Node 侧独立实现同一派生，由契约测试断言两侧一致。
 */
public final class IdempotencyKeys {

    public static final String PREFIX = "FLOW_IDK_";

    private IdempotencyKeys() {
    }

    public static String derive(String sourceRecordId, FlowType flowType, int businessVersion) {
        String raw = sourceRecordId + ":" + flowType.name() + ":" + businessVersion;
        return PREFIX + Hashes.sha256Hex(raw).substring(0, 16);
    }

    /** 审计/产物中引用的记录摘要；原始 record_id 不落审计。 */
    public static String recordRef(String sourceRecordId) {
        return Hashes.sha256Hex(sourceRecordId).substring(0, 12);
    }
}
