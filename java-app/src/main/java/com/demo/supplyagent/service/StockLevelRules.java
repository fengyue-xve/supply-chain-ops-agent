package com.demo.supplyagent.service;

/**
 * 库存水位档位计算：阈值本切片写死，后续配置化（TODO：阈值入配置/DB 表，供运营调整）。
 * 规则：availableQty &lt;= 0 -> out；&lt; LOW_MAX(20) -> low；&lt;= OK_MAX(200) -> ok；否则 high。
 */
public final class StockLevelRules {

    private StockLevelRules() {
    }

    /** low 区间上界（不含）：qty < 20 即 low */
    private static final int LOW_MAX = 20;
    /** ok 区间上界（含）：qty <= 200 即 ok */
    private static final int OK_MAX = 200;

    public static String of(int availableQty) {
        if (availableQty <= 0) {
            return "out";
        }
        if (availableQty < LOW_MAX) {
            return "low";
        }
        if (availableQty <= OK_MAX) {
            return "ok";
        }
        return "high";
    }
}
