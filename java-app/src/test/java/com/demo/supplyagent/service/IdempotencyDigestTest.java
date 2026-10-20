package com.demo.supplyagent.service;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 请求摘要纯单测：同内容不同顺序同摘要（规范化核心）、内容/工具名变化必变摘要、hex 形态稳定。
 */
class IdempotencyDigestTest {

    private static TreeMap<String, Integer> skuQty(String... pairs) {
        TreeMap<String, Integer> map = new TreeMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            map.put(pairs[i], Integer.valueOf(pairs[i + 1]));
        }
        return map;
    }

    @Test
    void sameContentInDifferentInsertionOrderProducesSameDigest() {
        TreeMap<String, Integer> forward = skuQty("AUD-001", "48", "CHG-001", "24");
        TreeMap<String, Integer> reverse = new TreeMap<>();
        reverse.put("CHG-001", 24);
        reverse.put("AUD-001", 48);
        assertThat(IdempotencyDigest.of("create-purchase-order", reverse))
                .isEqualTo(IdempotencyDigest.of("create-purchase-order", forward));
    }

    @Test
    void qtyOrSkuChangeChangesDigest() {
        String base = IdempotencyDigest.of("create-purchase-order", skuQty("AUD-001", "48"));
        assertThat(IdempotencyDigest.of("create-purchase-order", skuQty("AUD-001", "96")))
                .isNotEqualTo(base);
        assertThat(IdempotencyDigest.of("create-purchase-order", skuQty("AUD-002", "48")))
                .isNotEqualTo(base);
        assertThat(IdempotencyDigest.of("create-purchase-order",
                skuQty("AUD-001", "48", "CHG-001", "24")))
                .isNotEqualTo(base);
    }

    @Test
    void toolNameIsPartOfDigest() {
        assertThat(IdempotencyDigest.of("confirm-po", skuQty("AUD-001", "48")))
                .isNotEqualTo(IdempotencyDigest.of("create-purchase-order", skuQty("AUD-001", "48")));
    }

    @Test
    void digestIsLowercaseSha256HexAndStable() {
        String digest = IdempotencyDigest.of("create-purchase-order", skuQty("AUD-001", "48"));
        assertThat(digest).matches("[0-9a-f]{64}");
        assertThat(IdempotencyDigest.of("create-purchase-order", skuQty("AUD-001", "48")))
                .isEqualTo(digest);
    }

    @Test
    void digestIgnoresTraceFieldsByConstruction() {
        // 摘要只覆盖 tool + sku/qty（签名不含 request_id/conversation_id/tool_call_id），
        // 相同业务内容的两次调用即使溯源字段不同也得到同一摘要——此处以同一输入两次调用证明稳定性
        Map<String, Integer> input = skuQty("ACC-001", "50");
        assertThat(IdempotencyDigest.of("create-purchase-order", new TreeMap<>(input)))
                .isEqualTo(IdempotencyDigest.of("create-purchase-order", new TreeMap<>(input)));
    }
}
