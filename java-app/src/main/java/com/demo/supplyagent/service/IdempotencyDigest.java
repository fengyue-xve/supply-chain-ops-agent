package com.demo.supplyagent.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.SortedMap;

/**
 * 规范化请求摘要（请求级幂等用）：tool 名 + 按 sku 排序的「sku qty」逐行拼接后取 SHA-256 hex。
 * 排序输入（TreeMap）保证「输入顺序不同、内容相同」得到同一摘要；
 * 摘要只覆盖业务内容，不含 request_id/conversation_id/tool_call_id 等溯源字段。
 */
public final class IdempotencyDigest {

    private IdempotencyDigest() {
    }

    public static String of(String toolName, SortedMap<String, Integer> skuQty) {
        StringBuilder canonical = new StringBuilder(toolName).append('\n');
        skuQty.forEach((sku, qty) -> canonical.append(sku).append(' ').append(qty).append('\n'));
        return sha256Hex(canonical.toString());
    }

    private static String sha256Hex(String text) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }
}
