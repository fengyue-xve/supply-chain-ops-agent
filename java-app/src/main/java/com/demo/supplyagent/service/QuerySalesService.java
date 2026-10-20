package com.demo.supplyagent.service;

import com.demo.supplyagent.dto.ProductBrief;
import com.demo.supplyagent.dto.QuerySalesRequest;
import com.demo.supplyagent.dto.SalesDailyRow;
import com.demo.supplyagent.dto.SalesData;
import com.demo.supplyagent.dto.SalesItem;
import com.demo.supplyagent.dto.SalesSeriesPoint;
import com.demo.supplyagent.mapper.SalesQueryMapper;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 销量查询（只读，无写入/缓存）。
 * 基准日 = seed 固定最新业务日（2026-09-03，见 db/seed.sql 业务窗），不依赖系统时钟；
 * series 按日连续补 0；avgDailyQty = totalQty / days，HALF_UP 保留 2 位。
 */
@Service
public class QuerySalesService {

    public static final LocalDate LATEST_BUSINESS_DATE = LocalDate.of(2026, 9, 3);

    private final SalesQueryMapper salesQueryMapper;

    public QuerySalesService(SalesQueryMapper salesQueryMapper) {
        this.salesQueryMapper = salesQueryMapper;
    }

    public SalesData query(QuerySalesRequest request) {
        LocalDate start = LATEST_BUSINESS_DATE.minusDays(request.getDays() - 1L);
        String category = request.getCategory();
        if (category != null && !category.isBlank()) {
            return queryByCategory(category, request.getDays(), start);
        }
        return queryBySku(request.getSku(), request.getDays(), start);
    }

    private SalesData queryBySku(String sku, int days, LocalDate start) {
        ProductBrief brief = salesQueryMapper.selectProductBriefBySku(sku);
        if (brief == null) {
            return new SalesData(List.of(), List.of(sku), 0);
        }
        Map<String, Integer> daily = aggregate(salesQueryMapper.selectDailyTotals(List.of(sku), start, LATEST_BUSINESS_DATE))
                .getOrDefault(sku, Map.of());
        SalesItem item = toItem(brief, days, daily, start);
        return new SalesData(List.of(item), List.of(), 1);
    }

    private SalesData queryByCategory(String category, int days, LocalDate start) {
        List<ProductBrief> briefs = salesQueryMapper.selectCategoryProductBriefs(category);
        if (briefs.isEmpty()) {
            return SalesData.empty();
        }
        List<String> skus = new ArrayList<>();
        for (ProductBrief brief : briefs) {
            skus.add(brief.getSku());
        }
        Map<String, Map<String, Integer>> rowsBySku =
                aggregate(salesQueryMapper.selectDailyTotals(skus, start, LATEST_BUSINESS_DATE));
        List<SalesItem> items = new ArrayList<>();
        for (ProductBrief brief : briefs) {
            items.add(toItem(brief, days, rowsBySku.getOrDefault(brief.getSku(), Map.of()), start));
        }
        return new SalesData(items, List.of(), items.size());
    }

    private Map<String, Map<String, Integer>> aggregate(List<SalesDailyRow> rows) {
        Map<String, Map<String, Integer>> bySku = new HashMap<>();
        for (SalesDailyRow row : rows) {
            bySku.computeIfAbsent(row.getSku(), k -> new HashMap<>())
                    .put(row.getOrderDate(), row.getQty());
        }
        return bySku;
    }

    /** 以 [start, 基准日] 逐日铺满 series：无销量日期补 0，并累加出总量与日均。 */
    private SalesItem toItem(ProductBrief brief, int days, Map<String, Integer> daily, LocalDate start) {
        int totalQty = 0;
        List<SalesSeriesPoint> series = new ArrayList<>();
        for (LocalDate d = start; !d.isAfter(LATEST_BUSINESS_DATE); d = d.plusDays(1)) {
            int qty = daily.getOrDefault(d.toString(), 0);
            totalQty += qty;
            series.add(new SalesSeriesPoint(d.toString(), qty));
        }
        BigDecimal avgDailyQty = BigDecimal.valueOf(totalQty)
                .divide(BigDecimal.valueOf(days), 2, RoundingMode.HALF_UP);
        return new SalesItem(brief.getSku(), brief.getProductName(), brief.getCategory(),
                days, totalQty, avgDailyQty, series);
    }
}
