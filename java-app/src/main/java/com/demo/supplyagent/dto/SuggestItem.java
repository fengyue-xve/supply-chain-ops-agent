package com.demo.supplyagent.dto;

import lombok.Data;
import java.math.BigDecimal;

/** Read-only estimate. Quantities may be null when facts are missing; never execute from reason text. */
@Data
public class SuggestItem {
    private String sku;
    private String productName;
    private String category;
    private Integer currentQty;
    private BigDecimal avgDailyQty;
    /** Requested total coverage; already includes lead time. */
    private Integer targetCoverDays;
    private Integer safetyQty;
    private Integer suggestedQty;
    private String urgency;
    private String reason;
    private Integer leadDays;
    private Integer reviewDays;
    private Integer protectionDays;
    private Integer pkgQty;
    private String shelfStatus;
    private Integer targetQty;
    private Integer rawGap;
    private String stockSnapDate;
    private String salesWindowStart;
    private String salesWindowEnd;
    private String stockScope;
    /** ready / review_required / insufficient_data; all results remain suggestions requiring human review. */
    private String assessmentStatus;
}
