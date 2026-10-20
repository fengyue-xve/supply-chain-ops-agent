package com.demo.supplyagent.service;

import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import static org.junit.jupiter.api.Assertions.*;

class ReplenishRulesTest {
    @Test void productSafetyAndPackAreApplied() {
        var result = ReplenishRules.suggest(18, new BigDecimal("2.14"), 14, 60, 7, 24);
        assertEquals(90, result.targetQty());
        assertEquals(72, result.rawGap());
        assertEquals(72, result.suggestedQty());
        assertEquals("urgent", result.urgency());
        assertEquals(14, result.protectionDays());
        assertEquals(7, result.reviewDays());
    }
    @Test void leadTimeIsNotAddedTwice() {
        var result = ReplenishRules.suggest(18, new BigDecimal("2.14"), 7, 60, 7, 24);
        assertEquals(7, result.protectionDays());
        assertEquals(0, result.reviewDays());
        assertEquals(75, result.targetQty());
        assertEquals(57, result.rawGap());
        assertEquals(72, result.suggestedQty());
    }
    @Test void shortCoverageExpandsToLeadTime() {
        var result = ReplenishRules.suggest(3, BigDecimal.ONE, 7, 0, 21, 4);
        assertEquals(21, result.protectionDays());
        assertEquals(0, result.reviewDays());
        assertEquals(21, result.targetQty());
        assertEquals(20, result.suggestedQty());
    }
    @Test void packingDoesNotInflateUrgency() {
        var result = ReplenishRules.suggest(20, new BigDecimal("2"), 14, 0, 7, 100);
        assertEquals(8, result.rawGap());
        assertEquals(100, result.suggestedQty());
        assertEquals("normal", result.urgency());
    }
    @Test void stockSufficientNeverOrdersOneBox() {
        var result = ReplenishRules.suggest(500, new BigDecimal("2.14"), 14, 60, 7, 24);
        assertEquals(0, result.rawGap());
        assertEquals(0, result.suggestedQty());
        assertEquals("none", result.urgency());
    }
    @Test void outBeatsOtherUrgency() {
        var result = ReplenishRules.suggest(0, new BigDecimal("0.03"), 14, 20, 14, 10);
        assertEquals(21, result.targetQty());
        assertEquals(30, result.suggestedQty());
        assertEquals("out", result.urgency());
    }
    @Test void zeroDemandCanRefillProductSafety() {
        var result = ReplenishRules.suggest(5, BigDecimal.ZERO, 14, 20, 7, 24);
        assertEquals(24, result.suggestedQty());
        assertEquals("urgent", result.urgency());
    }
    @Test void exactlyLeadTimeThresholdIsNotUrgent() {
        var result = ReplenishRules.suggest(14, new BigDecimal("2"), 14, 0, 7, 1);
        assertEquals("normal", result.urgency());
    }
    @Test void missingDemandIsNotZeroDemand() {
        assertThrows(IllegalArgumentException.class,
                () -> ReplenishRules.suggest(0, null, 14, 20, 7, 24));
    }
    @Test void invalidPolicyIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> ReplenishRules.suggest(1, BigDecimal.ONE, 14, 20, 7, 0));
        assertThrows(IllegalArgumentException.class,
                () -> ReplenishRules.suggest(1, BigDecimal.ONE, 14, -1, 7, 24));
    }
}
