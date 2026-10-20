package com.demo.supplyagent.service;

import com.demo.supplyagent.dto.*;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class SuggestReplenishServiceTest {
    private final QueryStockLevelService stocks = mock(QueryStockLevelService.class);
    private final QuerySalesService sales = mock(QuerySalesService.class);
    private final SuggestReplenishService service = new SuggestReplenishService(stocks, sales);

    private StockLevelItem stock(String date, int leadDays) {
        StockLevelItem item = new StockLevelItem();
        item.setSku("AUD-001"); item.setProductName("Demo"); item.setCategory("audio");
        item.setAvailableQty(18); item.setSnapDate(date);
        item.setSafetyQty(60); item.setLeadDays(leadDays); item.setPkgQty(24); item.setShelfStatus("on_shelf");
        when(stocks.query(any())).thenReturn(new StockLevelData(List.of(item), List.of(), 1));
        return item;
    }
    private void demand() {
        when(sales.query(any())).thenReturn(new SalesData(List.of(new SalesItem("AUD-001", "Demo", "audio",
                7, 15, new BigDecimal("2.14"), List.of())), List.of(), 1));
    }
    private SuggestItem run(int coverDays) {
        SuggestReplenishRequest request = new SuggestReplenishRequest();
        request.setSkus(List.of("AUD-001")); request.setDays(7); request.setTargetCoverDays(coverDays);
        return service.suggest(request).getItems().getFirst();
    }
    @Test void responseCarriesReproducibleFactsAndProductPolicy() {
        stock("2026-09-03", 7); demand();
        SuggestItem result = run(14);
        assertEquals("ready", result.getAssessmentStatus());
        assertEquals("2026-08-28", result.getSalesWindowStart());
        assertEquals("2026-09-03", result.getSalesWindowEnd());
        assertEquals("on_hand_only", result.getStockScope());
        assertEquals(60, result.getSafetyQty()); assertEquals(24, result.getPkgQty());
        assertEquals(72, result.getSuggestedQty());
        assertTrue(result.getReason().contains("未计在途"));
        assertTrue(result.getReason().contains("尚无采集覆盖证明"));
    }
    @Test void differentStockDateRequiresReview() {
        stock("2026-09-04", 7); demand();
        SuggestItem result = run(14);
        assertEquals("review_required", result.getAssessmentStatus());
        assertTrue(result.getReason().contains("日期与销量截止日不同"));
    }
    @Test void expandedCoverageRequiresReview() {
        stock("2026-09-03", 21); demand();
        SuggestItem result = run(7);
        assertEquals("review_required", result.getAssessmentStatus());
        assertEquals(21, result.getProtectionDays()); assertEquals(0, result.getReviewDays());
        assertTrue(result.getReason().contains("已按提前期展开"));
    }
    @Test void missingSalesDoesNotBecomeZeroDemand() {
        stock("2026-09-03", 7);
        when(sales.query(any())).thenReturn(new SalesData(List.of(), List.of("AUD-001"), 0));
        SuggestItem result = run(14);
        assertEquals("insufficient_data", result.getAssessmentStatus());
        assertEquals("unknown", result.getUrgency()); assertNull(result.getAvgDailyQty());
        assertNull(result.getSuggestedQty()); assertNull(result.getTargetQty()); assertNull(result.getRawGap());
    }
    @Test void offShelfProductRequiresReviewAndCannotBeOrdered() {
        stock("2026-09-03", 7).setShelfStatus("off_shelf"); demand();
        SuggestItem result = run(14);
        assertEquals("review_required", result.getAssessmentStatus());
        assertEquals("off_shelf", result.getShelfStatus());
        assertTrue(result.getReason().contains("不可创建采购草稿"));
    }
    @Test void missingProductPolicyDoesNotFallBackToHardcodedSafety() {
        stock("2026-09-03", 7).setSafetyQty(null); demand();
        SuggestItem result = run(14);
        assertEquals("insufficient_data", result.getAssessmentStatus()); assertNull(result.getSuggestedQty());
    }
}
