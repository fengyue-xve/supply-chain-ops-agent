package com.demo.supplyagent.service;

import com.demo.supplyagent.dto.QueryStockLevelRequest;
import com.demo.supplyagent.dto.StockLevelData;
import com.demo.supplyagent.dto.StockLevelItem;
import com.demo.supplyagent.mapper.StockQueryMapper;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 库存水位查询（只读，无写入/缓存）。
 * skus 模式：命中按请求顺序进 items，未命中进 missingSkus，重复 sku 只计一次；
 * category 模式：先按类目取 SKU 再查最新快照，空类目返回空结果而非错误。
 */
@Service
public class QueryStockLevelService {

    private final StockQueryMapper stockQueryMapper;

    public QueryStockLevelService(StockQueryMapper stockQueryMapper) {
        this.stockQueryMapper = stockQueryMapper;
    }

    public StockLevelData query(QueryStockLevelRequest request) {
        String category = request.getCategory();
        if (category != null && !category.isBlank()) {
            return queryByCategory(category);
        }
        return queryBySkus(request.getSkus());
    }

    private StockLevelData queryByCategory(String category) {
        List<String> skus = stockQueryMapper.selectSkuCodesByCategory(category);
        if (skus.isEmpty()) {
            return StockLevelData.empty();
        }
        List<StockLevelItem> items = withLevels(stockQueryMapper.selectLatestBySkus(skus));
        return new StockLevelData(items, List.of(), items.size());
    }

    private StockLevelData queryBySkus(List<String> skus) {
        if (skus == null || skus.isEmpty()) {
            return StockLevelData.empty();
        }
        Map<String, StockLevelItem> bySku = new HashMap<>();
        for (StockLevelItem item : stockQueryMapper.selectLatestBySkus(skus)) {
            bySku.put(item.getSku(), item);
        }
        List<StockLevelItem> items = new ArrayList<>();
        List<String> missing = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (String sku : skus) {
            if (!seen.add(sku)) {
                continue;
            }
            StockLevelItem item = bySku.get(sku);
            if (item == null) {
                missing.add(sku);
            } else {
                item.setStockLevel(StockLevelRules.of(item.getAvailableQty()));
                items.add(item);
            }
        }
        return new StockLevelData(items, missing, items.size());
    }

    private static List<StockLevelItem> withLevels(List<StockLevelItem> items) {
        items.forEach(item -> item.setStockLevel(StockLevelRules.of(item.getAvailableQty())));
        return items;
    }
}
