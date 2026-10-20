package com.demo.supplyagent.service;

import com.demo.supplyagent.dto.*;
import org.springframework.stereotype.Service;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/** Combines read-only facts. This estimate excludes inbound PO quantities and reservations. */
@Service
public class SuggestReplenishService {
    public static final int DEFAULT_TARGET_COVER_DAYS = 14;
    private final QueryStockLevelService stockLevelService;
    private final QuerySalesService salesService;

    public SuggestReplenishService(QueryStockLevelService stockLevelService, QuerySalesService salesService) {
        this.stockLevelService = stockLevelService;
        this.salesService = salesService;
    }

    public SuggestData suggest(SuggestReplenishRequest request) {
        int coverDays = request.getTargetCoverDays() == null ? DEFAULT_TARGET_COVER_DAYS : request.getTargetCoverDays();
        QueryStockLevelRequest stockRequest = new QueryStockLevelRequest();
        if (request.getCategory() != null && !request.getCategory().isBlank()) {
            stockRequest.setCategory(request.getCategory());
        } else {
            stockRequest.setSkus(request.getSkus());
        }
        StockLevelData stockData = stockLevelService.query(stockRequest);
        List<SuggestItem> items = new ArrayList<>();
        for (StockLevelItem stock : stockData.getItems()) {
            QuerySalesRequest salesRequest = new QuerySalesRequest();
            salesRequest.setSku(stock.getSku());
            salesRequest.setDays(request.getDays());
            SalesData salesData = salesService.query(salesRequest);
            BigDecimal avg = salesData.getItems().isEmpty() ? null : salesData.getItems().get(0).getAvgDailyQty();
            items.add(toItem(stock, avg, coverDays, request.getDays()));
        }
        return new SuggestData(items, stockData.getMissingSkus(), items.size());
    }

    private SuggestItem toItem(StockLevelItem stock, BigDecimal avg, int coverDays, int days) {
        SuggestItem item = new SuggestItem();
        item.setSku(stock.getSku());
        item.setProductName(stock.getProductName());
        item.setCategory(stock.getCategory());
        item.setCurrentQty(stock.getAvailableQty());
        item.setAvgDailyQty(avg);
        item.setTargetCoverDays(coverDays);
        item.setSafetyQty(stock.getSafetyQty());
        item.setLeadDays(stock.getLeadDays());
        item.setPkgQty(stock.getPkgQty());
        item.setShelfStatus(stock.getShelfStatus());
        item.setStockSnapDate(stock.getSnapDate());
        item.setSalesWindowStart(QuerySalesService.LATEST_BUSINESS_DATE.minusDays(days - 1L).toString());
        item.setSalesWindowEnd(QuerySalesService.LATEST_BUSINESS_DATE.toString());
        item.setStockScope("on_hand_only");
        if (avg == null || stock.getAvailableQty() == null || stock.getAvailableQty() < 0
                || avg.signum() < 0 || stock.getSafetyQty() == null || stock.getSafetyQty() < 0
                || stock.getLeadDays() == null || stock.getLeadDays() < 0
                || stock.getPkgQty() == null || stock.getPkgQty() <= 0) {
            item.setAssessmentStatus("insufficient_data");
            item.setUrgency("unknown");
            item.setReason("缺少有效的销量、库存或商品补货参数，无法计算；缺失销量不能当作零需求。请补齐事实后人工复核。");
            return item;
        }
        ReplenishRules.Result result = ReplenishRules.suggest(stock.getAvailableQty(), avg, coverDays,
                stock.getSafetyQty(), stock.getLeadDays(), stock.getPkgQty());
        item.setSuggestedQty(result.suggestedQty());
        item.setUrgency(result.urgency());
        item.setProtectionDays(result.protectionDays());
        item.setReviewDays(result.reviewDays());
        item.setTargetQty(result.targetQty());
        item.setRawGap(result.rawGap());
        boolean differentDates = !item.getSalesWindowEnd().equals(stock.getSnapDate());
        boolean expanded = coverDays < stock.getLeadDays();
        boolean offShelf = !"on_shelf".equals(stock.getShelfStatus());
        item.setAssessmentStatus(differentDates || expanded || offShelf ? "review_required" : "ready");
        item.setReason(String.format(
                "库存快照%s现存%d件；销量窗口%s至%s（日均%s，演示窗口无订单日补零，尚无采集覆盖证明）。"
                        + "目标覆盖%d天（已含提前期%d天），有效保护期%d天、复核周期%d天；商品安全库存%d件。"
                        + "目标库存%d件，原始缺口%d件，按每箱%d件向上取整，建议补%d件。"
                        + "%s%s%s仅按现存库存估算，未扣预留、未计在途；须检查现有采购单并人工复核，不代表到货或库存增加。",
                stock.getSnapDate(), stock.getAvailableQty(), item.getSalesWindowStart(), item.getSalesWindowEnd(),
                avg.toPlainString(), coverDays, stock.getLeadDays(), result.protectionDays(), result.reviewDays(),
                stock.getSafetyQty(), result.targetQty(), result.rawGap(), stock.getPkgQty(), result.suggestedQty(),
                differentDates ? "库存日期与销量截止日不同，需人工复核。" : "",
                expanded ? "请求覆盖期短于提前期，已按提前期展开，需人工复核。" : "",
                offShelf ? "商品未上架，不可创建采购草稿，需人工复核。" : ""));
        return item;
    }
}
