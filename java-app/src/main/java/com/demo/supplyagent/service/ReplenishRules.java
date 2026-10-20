package com.demo.supplyagent.service;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** Pure on-hand replenishment estimate. The requested coverage already includes lead time. */
public final class ReplenishRules {
    private ReplenishRules() { }

    public static Result suggest(int currentQty, BigDecimal avgDailyQty, int targetCoverDays,
                                 int safetyQty, int leadDays, int pkgQty) {
        if (currentQty < 0 || avgDailyQty == null || avgDailyQty.signum() < 0
                || targetCoverDays <= 0 || safetyQty < 0 || leadDays < 0 || pkgQty <= 0) {
            throw new IllegalArgumentException("Invalid replenishment facts or product policy");
        }
        // Never add lead time twice. A requested coverage shorter than lead time is expanded.
        int protectionDays = Math.max(targetCoverDays, leadDays);
        int targetQty = avgDailyQty.multiply(BigDecimal.valueOf(protectionDays))
                .add(BigDecimal.valueOf(safetyQty)).setScale(0, RoundingMode.CEILING).intValueExact();
        int rawGap = Math.max(0, targetQty - currentQty);
        int suggestedQty = Math.toIntExact(((rawGap + (long) pkgQty - 1) / pkgQty) * pkgQty);
        BigDecimal leadTimeThreshold = avgDailyQty.multiply(BigDecimal.valueOf(leadDays))
                .add(BigDecimal.valueOf(safetyQty));
        String urgency = currentQty <= 0 ? "out"
                : BigDecimal.valueOf(currentQty).compareTo(leadTimeThreshold) < 0 ? "urgent"
                : rawGap > 0 ? "normal" : "none";
        return new Result(suggestedQty, urgency, protectionDays, protectionDays - leadDays,
                targetQty, rawGap);
    }

    public record Result(int suggestedQty, String urgency, int protectionDays, int reviewDays,
                         int targetQty, int rawGap) { }
}
