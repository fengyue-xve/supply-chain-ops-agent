package com.demo.supplyagent.service;

import com.demo.supplyagent.dto.PoPendingData;
import com.demo.supplyagent.dto.PoPendingItem;
import com.demo.supplyagent.dto.PoPendingRequest;
import com.demo.supplyagent.mapper.PurchasePoMapper;
import com.demo.supplyagent.web.ApiException;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * W4 待确认采购单查询（只读）：仅返回 purchase_po.status='pending' 的草稿行，单表零 JOIN。
 * 归一化：page 缺省 1（>=1）、size 缺省 20（1~100）；OFFSET 用 long 计算（防 int 溢出）。
 * status='pending' 是表内四态之一（chk_po_status 约束），与写路径「只落草稿」语义一致。
 */
@Service
public class PoPendingService {

    private static final int DEFAULT_PAGE = 1;
    private static final int DEFAULT_SIZE = 20;
    private static final int MIN_SIZE = 1;
    private static final int MAX_SIZE = 100;

    private final PurchasePoMapper purchasePoMapper;

    public PoPendingService(PurchasePoMapper purchasePoMapper) {
        this.purchasePoMapper = purchasePoMapper;
    }

    public PoPendingData query(PoPendingRequest request) {
        int page = normalizePage(request.getPage());
        int size = normalizeSize(request.getSize());
        long offset = (long) (page - 1) * size;
        long total = purchasePoMapper.countPending();
        List<PoPendingItem> items = purchasePoMapper.selectPendingPage(size, offset);
        return new PoPendingData(items, total, page, size);
    }

    private static int normalizePage(Integer page) {
        if (page == null) {
            return DEFAULT_PAGE;
        }
        if (page < DEFAULT_PAGE) {
            throw new ApiException(400, "page 必须大于等于 1");
        }
        return page;
    }

    private static int normalizeSize(Integer size) {
        if (size == null) {
            return DEFAULT_SIZE;
        }
        if (size < MIN_SIZE || size > MAX_SIZE) {
            throw new ApiException(400, "size 必须在 1~100 之间");
        }
        return size;
    }
}
