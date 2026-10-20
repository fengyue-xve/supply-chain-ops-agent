package com.demo.supplyagent.service.tool;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 全部工具（3 只读 + 2 敏感写）的 input/output schema 声明（纯数据，与 contracts/tools-schema.json 语义一致；
 * 快照文件与注册表的一致性由测试锁死，本文件为注册表侧的源）。
 * JSON 以 text block 书写并解析为 Map，schema 声明即所见，避免嵌套 Map 字面量的括号地狱。
 */
public final class ToolSchemas {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public static final Map<String, Object> QUERY_STOCK_LEVEL_INPUT = json("""
            {
              "type": "object",
              "properties": {
                "skus": {
                  "type": "array",
                  "items": {"type": "string"},
                  "maxItems": 20,
                  "description": "SKU 列表，如 AUD-001；与 category 二选一，最多 20 个"
                },
                "category": {
                  "type": "string",
                  "enum": ["audio", "charging", "accessory"],
                  "description": "商品类目；与 skus 二选一"
                }
              },
              "oneOf": [
                {"required": ["skus"]},
                {"required": ["category"]}
              ]
            }
            """);

    public static final Map<String, Object> QUERY_STOCK_LEVEL_OUTPUT = json("""
            {
              "type": "object",
              "description": "统一响应信封 data 部分的业务结构",
              "properties": {
                "items": {
                  "type": "array",
                  "description": "命中条目，每条含 sku、productName、category、availableQty、stockLevel(out/low/ok/high)、snapDate(yyyy-MM-dd)",
                  "items": {"type": "object"}
                },
                "missingSkus": {
                  "type": "array",
                  "items": {"type": "string"},
                  "description": "查无快照/商品的 SKU，原样返回供追问，不整单失败"
                },
                "total": {"type": "integer", "description": "命中条数 = items.length"}
              },
              "required": ["items", "missingSkus", "total"]
            }
            """);

    public static final Map<String, Object> QUERY_SALES_INPUT = json("""
            {
              "type": "object",
              "properties": {
                "sku": {
                  "type": "string",
                  "description": "单品 SKU，如 AUD-001；与 category 二选一"
                },
                "category": {
                  "type": "string",
                  "enum": ["audio", "charging", "accessory"],
                  "description": "商品类目；与 sku 二选一"
                },
                "days": {
                  "type": "integer",
                  "enum": [7, 30],
                  "description": "统计窗口天数，必填"
                }
              },
              "required": ["days"],
              "oneOf": [
                {"required": ["sku"]},
                {"required": ["category"]}
              ]
            }
            """);

    public static final Map<String, Object> QUERY_SALES_OUTPUT = json("""
            {
              "type": "object",
              "description": "统一响应信封 data 部分的业务结构",
              "properties": {
                "items": {
                  "type": "array",
                  "description": "命中条目，每条含 sku、productName、category、days、totalQty、avgDailyQty(2 位小数)、series(逐日数组，元素 {date: yyyy-MM-dd, qty})",
                  "items": {"type": "object"}
                },
                "missingSkus": {
                  "type": "array",
                  "items": {"type": "string"},
                  "description": "查无销量记录的 SKU，原样返回供追问"
                },
                "total": {"type": "integer", "description": "命中条数 = items.length"}
              },
              "required": ["items", "missingSkus", "total"]
            }
            """);

    public static final Map<String, Object> SUGGEST_REPLENISH_INPUT = json("""
            {
              "type": "object",
              "properties": {
                "skus": {
                  "type": "array",
                  "items": {"type": "string"},
                  "maxItems": 20,
                  "description": "SKU 列表，如 AUD-001；与 category 二选一，最多 20 个"
                },
                "category": {
                  "type": "string",
                  "enum": ["audio", "charging", "accessory"],
                  "description": "商品类目；与 skus 二选一"
                },
                "days": {
                  "type": "integer",
                  "enum": [7, 30],
                  "description": "日均销量的统计窗口天数，必填"
                },
                "targetCoverDays": {
                  "type": "integer",
                  "enum": [7, 14, 30],
                  "default": 14,
                  "description": "已含采购提前期的总覆盖期，缺省14；有效保护期=max(该值,商品提前期)，不重复相加"
                }
              },
              "required": ["days"],
              "oneOf": [
                {"required": ["skus"]},
                {"required": ["category"]}
              ]
            }
            """);

    public static final Map<String, Object> SUGGEST_REPLENISH_OUTPUT = json("""
            {
              "type": "object",
              "description": "统一响应信封 data 部分的业务结构",
              "properties": {
                "items": {
                  "type": "array",
                  "description": "建议条目含sku、currentQty、avgDailyQty、targetCoverDays(请求总覆盖期)、leadDays、reviewDays、protectionDays、safetyQty(商品安全库存)、pkgQty、targetQty、rawGap、suggestedQty(整箱)、urgency(out/urgent/normal/none/unknown)、stockSnapDate、salesWindowStart、salesWindowEnd、stockScope(on_hand_only)、assessmentStatus(ready/review_required/insufficient_data)、reason；缺失事实时计算量为null，日期错配或覆盖期不足须复核",
                  "items": {"type": "object"}
                },
                "missingSkus": {
                  "type": "array",
                  "items": {"type": "string"},
                  "description": "查无快照的 SKU，原样返回供追问"
                },
                "total": {"type": "integer", "description": "建议条数 = items.length"}
              },
              "required": ["items", "missingSkus", "total"]
            }
            """);

    public static final Map<String, Object> CREATE_PURCHASE_ORDER_INPUT = json("""
            {
              "type": "object",
              "properties": {
                "items": {
                  "type": "array",
                  "maxItems": 20,
                  "description": "采购明细，1–20 条且 SKU 不重复；幂等键经 Idempotency-Key 请求头传入，不属于请求体",
                  "items": {
                    "type": "object",
                    "properties": {
                      "sku": {"type": "string", "description": "SKU，大写字母/数字/短横线，如 AUD-001"},
                      "qty": {"type": "integer", "description": "采购数量，>0 且为包装数量整数倍"}
                    },
                    "required": ["sku", "qty"]
                  }
                }
              },
              "required": ["items"]
            }
            """);

    public static final Map<String, Object> CREATE_PURCHASE_ORDER_OUTPUT = json("""
            {
              "type": "object",
              "description": "统一响应信封 data 部分的业务结构",
              "properties": {
                "items": {
                  "type": "array",
                  "description": "生成的采购单草稿，每条含 poNo、sku、qty、status(恒为 pending)",
                  "items": {"type": "object"}
                },
                "total": {"type": "integer", "description": "生成条数 = items.length"},
                "duplicated": {"type": "boolean", "description": "true=同键同参的幂等回放（零写入），false=本次新建"}
              },
              "required": ["items", "total", "duplicated"]
            }
            """);

    public static final Map<String, Object> CONFIRM_PO_INPUT = json("""
            {
              "type": "object",
              "properties": {
                "poNo": {"type": "string", "description": "待确认的采购单号，如 PO20260911001"}
              },
              "required": ["poNo"]
            }
            """);

    public static final Map<String, Object> CONFIRM_PO_OUTPUT = json("""
            {
              "type": "object",
              "description": "统一响应信封 data 部分的业务结构",
              "properties": {
                "poNo": {"type": "string", "description": "采购单号"},
                "sku": {"type": "string", "description": "SKU"},
                "qty": {"type": "integer", "description": "采购数量"},
                "status": {"type": "string", "description": "确认后恒为 confirmed"}
              },
              "required": ["poNo", "sku", "qty", "status"]
            }
            """);

    private ToolSchemas() {
    }

    private static Map<String, Object> json(String text) {
        try {
            return MAPPER.readValue(text, new TypeReference<LinkedHashMap<String, Object>>() {
            });
        } catch (Exception e) {
            throw new IllegalStateException("schema 声明不是合法 JSON", e);
        }
    }
}
