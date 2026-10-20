package com.demo.supplyagent.service;

import com.demo.supplyagent.dto.ConfirmPoData;
import com.demo.supplyagent.dto.CreatePurchaseOrderData;
import com.demo.supplyagent.dto.CreatePurchaseOrderRequest;
import com.demo.supplyagent.dto.ProductWriteInfo;
import com.demo.supplyagent.dto.WriteContext;
import com.demo.supplyagent.dto.WriteError;
import com.demo.supplyagent.entity.AgentHistory;
import com.demo.supplyagent.entity.IdempotencyRecord;
import com.demo.supplyagent.entity.PoConfirmation;
import com.demo.supplyagent.entity.PurchasePo;
import com.demo.supplyagent.mapper.AgentHistoryMapper;
import com.demo.supplyagent.mapper.IdempotencyRecordMapper;
import com.demo.supplyagent.mapper.PoConfirmationMapper;
import com.demo.supplyagent.mapper.PoNoSeqMapper;
import com.demo.supplyagent.mapper.ProductMapper;
import com.demo.supplyagent.mapper.PurchasePoMapper;
import com.demo.supplyagent.web.ApiException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 敏感写工具（W3-③）：create-purchase-order 落 pending 草稿、confirm-po 人工确认生效。
 *
 * 事务边界用 TransactionTemplate 而非 @Transactional，原因有二：
 *  ① create 需要在「整事务失败」后于事务外回查幂等登记决定回放/409（代理注解无法表达事务外语义）；
 *  ② create 需要整请求有界重试（编号分配冷启动窗口可能死锁；失败事务已整体回滚，重试零残留）。
 * confirm 无编号分配、只做单行条件更新，不需要重试；两者共用同一模板。
 *
 * 错误语义：400 参数/业务校验（零写入，不落审计）、404 采购单不存在、
 * 409 幂等键参数冲突 / 同 SKU 已有未确认意向 / 重复确认 / 确认时 SKU 下架（此时 failed+cancelled 已提交并落审计）、
 * 500 库异常（整事务回滚，绝不伪装成功）。拒绝且无状态迁移的请求不落审计。
 * 未确认意向防重跨操作员与幂等键生效；confirmed 仅表示人工确认，不表示到货或入库。
 * 商品主档行锁是应用创建路径的并发边界；采购写入必须经此服务，不能绕过它直写。
 */
@Service
public class PoWriteService {

    public static final String TOOL_CREATE = "create-purchase-order";
    public static final String TOOL_CONFIRM = "confirm-po";

    public static final String PO_STATUS_PENDING = "pending";
    public static final String PO_STATUS_CONFIRMED = "confirmed";
    public static final String PO_STATUS_CANCELLED = "cancelled";

    public static final String CONF_STATUS_PENDING = "pending_confirmation";
    public static final String CONF_STATUS_CONFIRMED = "confirmed";
    public static final String CONF_STATUS_FAILED = "failed";

    public static final String ACTION_PO_CREATE = "po_create";
    public static final String ACTION_PO_CONFIRM = "po_confirm";
    public static final String ACTION_PO_CONFIRM_FAIL = "po_confirm_fail";

    private static final String SHELF_ON = "on_shelf";
    private static final String FAIL_REASON_UNSELLABLE = "SKU 不存在或已下架";
    private static final int MAX_WRITE_ATTEMPTS = 3;
    private static final DateTimeFormatter PO_DATE = DateTimeFormatter.BASIC_ISO_DATE;

    private final TransactionTemplate transactionTemplate;
    private final ProductMapper productMapper;
    private final PurchasePoMapper purchasePoMapper;
    private final PoConfirmationMapper poConfirmationMapper;
    private final IdempotencyRecordMapper idempotencyRecordMapper;
    private final AgentHistoryMapper agentHistoryMapper;
    private final PoNoSeqMapper poNoSeqMapper;
    private final ObjectMapper objectMapper;

    public PoWriteService(PlatformTransactionManager transactionManager,
                          ProductMapper productMapper,
                          PurchasePoMapper purchasePoMapper,
                          PoConfirmationMapper poConfirmationMapper,
                          IdempotencyRecordMapper idempotencyRecordMapper,
                          AgentHistoryMapper agentHistoryMapper,
                          PoNoSeqMapper poNoSeqMapper,
                          ObjectMapper objectMapper) {
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.productMapper = productMapper;
        this.purchasePoMapper = purchasePoMapper;
        this.poConfirmationMapper = poConfirmationMapper;
        this.idempotencyRecordMapper = idempotencyRecordMapper;
        this.agentHistoryMapper = agentHistoryMapper;
        this.poNoSeqMapper = poNoSeqMapper;
        this.objectMapper = objectMapper;
    }

    // ---------- create-purchase-order ----------

    public CreatePurchaseOrderData create(CreatePurchaseOrderRequest request, WriteContext ctx) {
        List<CreatePurchaseOrderRequest.Item> sortedItems = request.getItems().stream()
                .sorted(Comparator.comparing(CreatePurchaseOrderRequest.Item::getSku))
                .toList();
        TreeMap<String, Integer> canonical = new TreeMap<>();
        sortedItems.forEach(item -> canonical.put(item.getSku(), item.getQty()));
        String digest = IdempotencyDigest.of(TOOL_CREATE, canonical);

        for (int attempt = 1; ; attempt++) {
            try {
                return transactionTemplate.execute(status -> doCreate(sortedItems, digest, ctx));
            } catch (DuplicateKeyException e) {
                // 撞唯一键的事务已整体回滚：事务外按 (工具,操作者,键) 作用域回查登记裁决（回放 / 409 / 非幂等冲突再抛）
                return replayOrReject(ctx, digest, e);
            } catch (PessimisticLockingFailureException e) {
                // 编号分配冷启动窗口的死锁/锁超时：失败事务零残留，整请求有界重试
                if (attempt >= MAX_WRITE_ATTEMPTS) {
                    throw e;
                }
            }
        }
    }

    private CreatePurchaseOrderData doCreate(List<CreatePurchaseOrderRequest.Item> sortedItems,
                                             String digest, WriteContext ctx) {
        LocalDateTime now = LocalDateTime.now();

        // 所有创建入口都按相同 SKU 顺序锁商品，校验与 pending 检查在同一事务内。
        Map<String, ProductWriteInfo> products = loadProductsForUpdate(sortedItems);
        // 回放优先于当前商品参数及未确认意向检查：历史成功不会因主档变化变成失败。
        IdempotencyRecord previous = idempotencyRecordMapper.selectByScope(
                TOOL_CREATE, ctx.operator(), ctx.idempotencyKey());
        if (previous != null) {
            return replayRecord(previous, ctx, digest);
        }
        List<WriteError> errors = validateItems(sortedItems, products);
        if (!errors.isEmpty()) {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("errors", errors);
            throw new ApiException(400, "参数校验未通过", data);
        }

        // 批量请求先检查全部 SKU；任意冲突时编号、幂等登记、业务与审计均零写入。
        List<Map<String, Object>> conflicts = new ArrayList<>();
        List<Map<String, Object>> existingOrders = new ArrayList<>();
        for (CreatePurchaseOrderRequest.Item item : sortedItems) {
            for (PurchasePo pending : purchasePoMapper.selectPendingBySkuForUpdate(item.getSku())) {
                Map<String, Object> conflict = new LinkedHashMap<>();
                conflict.put("sku", item.getSku());
                conflict.put("existingPoNo", pending.getPoNo());
                conflict.put("existingQty", pending.getQty());
                conflict.put("status", pending.getStatus());
                conflicts.add(conflict);
                existingOrders.add(Map.of("poNo", pending.getPoNo(), "sku", pending.getSkuCode(),
                        "qty", pending.getQty(), "status", pending.getStatus()));
            }
        }
        if (!conflicts.isEmpty()) {
            Map<String, Object> conflictData = new LinkedHashMap<>(conflicts.get(0));
            conflictData.put("reason", "pending_intent_exists");
            conflictData.put("error", "pending_purchase_exists");
            conflictData.put("existingOrders", existingOrders);
            conflictData.put("conflicts", conflicts);
            throw new ApiException(409, "商品已有待确认采购意向，请先处理现有草稿", conflictData);
        }

        // 编号分配：单语句原子自增（随事务回滚），区间 [first, last] 按 sku 排序依次编号
        LocalDate today = LocalDate.now();
        poNoSeqMapper.allocate(today, sortedItems.size(), now);
        long last = poNoSeqMapper.selectLastInsertId();
        long first = last - sortedItems.size() + 1;

        List<CreatePurchaseOrderData.Line> lines = new ArrayList<>(sortedItems.size());
        for (int i = 0; i < sortedItems.size(); i++) {
            CreatePurchaseOrderRequest.Item item = sortedItems.get(i);
            lines.add(new CreatePurchaseOrderData.Line(
                    poNo(today, first + i), item.getSku(), item.getQty(), PO_STATUS_PENDING));
        }
        CreatePurchaseOrderData data = new CreatePurchaseOrderData(lines, false);

        // 幂等登记先于业务行：同操作者同 key 并发在事务早期即被唯一键挡住（整体回滚后由事务外按作用域回查裁决）
        IdempotencyRecord record = new IdempotencyRecord();
        record.setIdempotencyKey(ctx.idempotencyKey());
        record.setToolName(TOOL_CREATE);
        record.setRequestDigest(digest);
        record.setResponseJson(toJson(data));
        record.setOperator(ctx.operator());
        record.setRequestId(ctx.requestId());
        record.setConversationId(ctx.conversationId());
        record.setToolCallId(ctx.toolCallId());
        record.setCreatedAt(now);
        record.setUpdatedAt(now);
        idempotencyRecordMapper.insert(record);

        for (CreatePurchaseOrderData.Line line : lines) {
            insertPendingPo(line, now);
            insertPendingConfirmation(line, ctx, now);
            insertHistory(line.getPoNo(), ACTION_PO_CREATE,
                    null, statusSnapshot(PO_STATUS_PENDING), ctx, now);
        }
        return data;
    }

    private void insertPendingPo(CreatePurchaseOrderData.Line line, LocalDateTime now) {
        PurchasePo po = new PurchasePo();
        po.setPoNo(line.getPoNo());
        po.setSkuCode(line.getSku());
        po.setQty(line.getQty());
        po.setStatus(PO_STATUS_PENDING);
        po.setManualConfirmRequired(true);
        po.setCreatedAt(now);
        po.setUpdatedAt(now);
        purchasePoMapper.insert(po);
    }

    private void insertPendingConfirmation(CreatePurchaseOrderData.Line line, WriteContext ctx,
                                           LocalDateTime now) {
        PoConfirmation conf = new PoConfirmation();
        conf.setPoNo(line.getPoNo());
        conf.setSkuCode(line.getSku());
        conf.setQty(line.getQty());
        conf.setStatus(CONF_STATUS_PENDING);
        conf.setIdempotencyKey(ctx.idempotencyKey());
        conf.setOperator(ctx.operator());
        conf.setRequestId(ctx.requestId());
        conf.setConversationId(ctx.conversationId());
        conf.setToolCallId(ctx.toolCallId());
        conf.setCreatedAt(now);
        conf.setUpdatedAt(now);
        poConfirmationMapper.insert(conf);
    }

    /**
     * 事务外回查（作用域 = 工具 + 操作者 + 键）：登记行在且摘要一致 → 回放（零写入）；
     * 摘要不一致 → 409；登记行不在（撞的是其它唯一键，如 po_no）→ 原异常（500 语义）。
     * 按作用域回查保证：同 key 的不同操作者互不相认，绝不回放他人历史结果。
     */
    private CreatePurchaseOrderData replayOrReject(WriteContext ctx, String digest, DuplicateKeyException original) {
        IdempotencyRecord existing =
                idempotencyRecordMapper.selectByScope(TOOL_CREATE, ctx.operator(), ctx.idempotencyKey());
        if (existing == null) {
            throw original;
        }
        return replayRecord(existing, ctx, digest);
    }

    private CreatePurchaseOrderData replayRecord(IdempotencyRecord existing, WriteContext ctx, String digest) {
        if (!existing.getRequestDigest().equals(digest)) {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("idempotencyKey", ctx.idempotencyKey());
            throw new ApiException(409, "该幂等键已用于不同参数的请求，拒绝执行", data);
        }
        CreatePurchaseOrderData stored = fromJson(existing.getResponseJson());
        return new CreatePurchaseOrderData(stored.getItems(), true);
    }

    // ---------- confirm-po ----------

    private sealed interface ConfirmOutcome {
        record Confirmed(ConfirmPoData data) implements ConfirmOutcome {
        }

        record Conflict(String poNo, String status, String message) implements ConfirmOutcome {
        }

        /** 条件更新受影响 0 行（并发已被他人迁移）：事务外回查当前状态后给 409。 */
        record StaleSnapshot(String poNo) implements ConfirmOutcome {
        }
    }

    public ConfirmPoData confirm(String poNo, WriteContext ctx) {
        // 提交语义不变量：事务用 TransactionTemplate 显式管理，控制流靠 outcome 返回值而非异常——
        // doConfirm 正常返回即提交（含 SKU 下架的 failed+cancelled+审计），409 一律在事务外抛出。
        // 禁止改成 @Transactional + RuntimeException（会被自动回滚），也禁止宽泛 noRollbackFor=ApiException.class。
        ConfirmOutcome outcome = transactionTemplate.execute(status -> doConfirm(poNo, ctx));
        if (outcome instanceof ConfirmOutcome.Confirmed confirmed) {
            return confirmed.data();
        }
        if (outcome instanceof ConfirmOutcome.StaleSnapshot stale) {
            PoConfirmation current = poConfirmationMapper.selectByPoNo(stale.poNo());
            if (current == null) {
                throw new ApiException(404, "采购单不存在");
            }
            outcome = new ConfirmOutcome.Conflict(stale.poNo(), current.getStatus(),
                    conflictMessage(current.getStatus()));
        }
        ConfirmOutcome.Conflict conflict = (ConfirmOutcome.Conflict) outcome;
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("poNo", conflict.poNo());
        data.put("status", conflict.status());
        throw new ApiException(409, conflict.message(), data);
    }

    private ConfirmOutcome doConfirm(String poNo, WriteContext ctx) {
        PoConfirmation conf = poConfirmationMapper.selectByPoNo(poNo);
        if (conf == null) {
            throw new ApiException(404, "采购单不存在");
        }
        if (!CONF_STATUS_PENDING.equals(conf.getStatus())) {
            return new ConfirmOutcome.Conflict(poNo, conf.getStatus(), conflictMessage(conf.getStatus()));
        }

        LocalDateTime now = LocalDateTime.now();
        ProductWriteInfo product = productMapper.selectWriteInfoBySku(conf.getSkuCode());
        boolean sellable = product != null && SHELF_ON.equals(product.getShelfStatus());

        if (!sellable) {
            // 确认时 SKU 不可售：不可恢复 → 确认流 failed + 采购单取消 + 审计，随后 409
            if (poConfirmationMapper.failTransition(poNo, FAIL_REASON_UNSELLABLE, now) == 0) {
                return new ConfirmOutcome.StaleSnapshot(poNo);
            }
            if (purchasePoMapper.cancelPending(poNo, now) == 0) {
                throw new IllegalStateException("采购单与确认流状态不一致：" + poNo);
            }
            insertHistory(poNo, ACTION_PO_CONFIRM_FAIL,
                    statusSnapshot(PO_STATUS_PENDING), statusSnapshot(PO_STATUS_CANCELLED), ctx, now);
            return new ConfirmOutcome.Conflict(poNo, CONF_STATUS_FAILED,
                    "SKU 不存在或已下架，采购单已置为 failed 并取消");
        }

        if (poConfirmationMapper.confirmTransition(poNo, now) == 0) {
            return new ConfirmOutcome.StaleSnapshot(poNo);
        }
        if (purchasePoMapper.confirmPending(poNo, now) == 0) {
            throw new IllegalStateException("采购单与确认流状态不一致：" + poNo);
        }
        insertHistory(poNo, ACTION_PO_CONFIRM,
                statusSnapshot(PO_STATUS_PENDING), statusSnapshot(PO_STATUS_CONFIRMED), ctx, now);
        return new ConfirmOutcome.Confirmed(
                new ConfirmPoData(poNo, conf.getSkuCode(), conf.getQty(), PO_STATUS_CONFIRMED));
    }

    private String conflictMessage(String status) {
        return "采购单当前状态为 " + status + "，不能执行确认";
    }

    // ---------- 共用 ----------

    private Map<String, ProductWriteInfo> loadProductsForUpdate(List<CreatePurchaseOrderRequest.Item> items) {
        Map<String, ProductWriteInfo> bySku = new LinkedHashMap<>();
        for (CreatePurchaseOrderRequest.Item item : items) {
            ProductWriteInfo product = productMapper.selectWriteInfoForUpdate(item.getSku());
            if (product != null) {
                bySku.put(product.getSku(), product);
            }
        }
        return bySku;
    }

    private List<WriteError> validateItems(List<CreatePurchaseOrderRequest.Item> items,
                                           Map<String, ProductWriteInfo> products) {
        List<WriteError> errors = new ArrayList<>();
        for (CreatePurchaseOrderRequest.Item item : items) {
            if (item.getQty() <= 0) {
                errors.add(new WriteError(item.getSku(), "invalid_qty", "数量必须为正整数"));
                continue;
            }
            ProductWriteInfo product = products.get(item.getSku());
            if (product == null) {
                errors.add(new WriteError(item.getSku(), "sku_not_found", "SKU 不存在"));
                continue;
            }
            if (!SHELF_ON.equals(product.getShelfStatus())) {
                errors.add(new WriteError(item.getSku(), "sku_off_shelf", "SKU 已下架"));
                continue;
            }
            Integer pkgQty = product.getPkgQty();
            if (pkgQty == null || pkgQty <= 0) {
                errors.add(new WriteError(item.getSku(), "invalid_pkg_qty", "商品包装数量无效，请先修正主档"));
                continue;
            }
            if (item.getQty() % pkgQty != 0) {
                WriteError error = new WriteError(item.getSku(), "qty_not_pkg_multiple",
                        "数量必须为包装数量的整数倍");
                error.setQty(item.getQty());
                error.setPkgQty(pkgQty);
                long rounded = ((long) item.getQty() + pkgQty - 1) / pkgQty * pkgQty;
                if (rounded <= Integer.MAX_VALUE) {
                    error.setSuggestedQty((int) rounded);
                } else {
                    error.setCode("qty_out_of_range");
                    error.setMessage("按包装数量取整后超过可支持数量，请拆分业务需求");
                }
                errors.add(error);
            }
        }
        return errors;
    }

    /** 审计行：关键检索字段全部结构化落列；before/after 只存状态快照。 */
    private void insertHistory(String poNo, String actionType, String beforeJson, String afterJson,
                               WriteContext ctx, LocalDateTime now) {
        AgentHistory row = new AgentHistory();
        row.setTaskKey(poNo);
        row.setExecutedAt(now);
        row.setActionType(actionType);
        row.setWho(ctx.role());
        row.setOperator(ctx.operator());
        row.setBeforeJson(beforeJson);
        row.setAfterJson(afterJson);
        row.setRequestedAt(now);
        row.setRequestId(ctx.requestId());
        row.setConversationId(ctx.conversationId());
        row.setToolCallId(ctx.toolCallId());
        row.setCreatedAt(now);
        row.setUpdatedAt(now);
        agentHistoryMapper.insert(row);
    }

    private String poNo(LocalDate date, long seq) {
        return "PO" + date.format(PO_DATE) + String.format("%03d", seq);
    }

    private String statusSnapshot(String status) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("status", status);
        return toJson(snapshot);
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("JSON 序列化失败", e);
        }
    }

    private CreatePurchaseOrderData fromJson(String json) {
        try {
            return objectMapper.readValue(json, CreatePurchaseOrderData.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("幂等登记内容不可解析", e);
        }
    }
}
