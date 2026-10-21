# 按业务理解代码

1. 从 `java-app/.../service/SuggestReplenishService.java`、`ReplenishRules.java` 和 `StockQueryMapper.java` 看事实如何被读取、如何处理缺失与日期、如何整箱取整。先手算AUD-001和DEM-001，再对照DTO与页面字段。
2. 从 `CreatePurchaseOrderController` → `PoWriteService` 看结构校验、身份、幂等回放、锁顺序、pending冲突、事务提交；对照 `db/05_w4_pending_po_lookup.sql` 的查询索引。区分请求去重和业务去重。
3. 从 `web-app/src/components/PurchaseDraftPanel.vue` 看人工复核、先保存再发送、未知结果恢复；从 `PoPanel.vue` 看独立单号确认。localStorage只帮助恢复，不是后端权限或一致性机制。
4. 从 `fastapi-service/app/agent/orchestrator.py` 看模型工具循环、允许调用的工具和错误处理；从 `app/models/` 对照demo脚本与DeepSeek接入。数量与权限仍属于Java。
5. 从 `app/rag/retriever_v3.py` 看词法候选与字段/短语过滤，配合 `evals/cases/current-business*` 看规则集与改写检查，理解失败原因。当前默认 `hybrid.py` 增加本地确定性特征向量、业务门控和RRF；不是训练模型。结合 `evals/hybrid_ablation.py` 对照四种检索器。
6. 从 `java-app/.../gateway/FormsReadClient.java` 看固定路径、专用reader身份、字段白名单和错误映射，再看表单Java的授权、计划、审批、apply/replay和Pi `src/scenarios.ts`。

路径中的 `...` 是包目录 `src/main/java/com/demo/supplyagent`。两套Java各有独立权限和持久化边界，运营JWT不直接成为表单审批身份。

讲清三个关键区别：模型意图与业务事实、请求重试与新采购意向、表单回填完成与真实物流履约。

7. 从 `app/api/mcp.py` 对照标准MCP发现/调用/Resource，从 `live_trace.py`、Java `AgentTraceClient` 到Vue `AgentTracePanel` 跟踪上下文和停因；详见 [升级说明](08-agent-upgrade.md)。
