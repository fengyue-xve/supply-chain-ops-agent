# AI Agent升级：Hybrid RAG、MCP与运行轨迹

## 运行与真实范围

现有Java业务规则、采购人工确认和表单审批边界继续生效。升级增加检索、标准协议入口和可观察编排；MCP不会将写权限交给模型。默认业务助手仍为明确标识的离线demo，真实DeepSeek需自行配置Key，未测外部模型质量。

## Hybrid RAG消融

`app/rag/hybrid.py` 将四层词法规则与512维归一化特征哈希向量按RRF（k=60）融合。特征包括汉字二/三元组、ASCII字段及显式业务概念，使用语料IDF。它是本地确定性向量分支，不是神经语义Embedding或自训练模型；无向量数据库依赖。字段/业务概念门控防止强字段误召回，向量异常时退回Lexical v3。默认 `RAG_RETRIEVER=hybrid`，可改为 `lexical-v3`。

```sh
cd fastapi-service
python -m evals.hybrid_ablation --out ../artifacts/hybrid-ablation.json
```

| 检索器 | 开发30严格通过 | 开发Recall@3 | 禁止片段数 | 已见改写12严格通过 | 改写Recall@3 | 禁止片段数 |
| --- | --- | --- | --- | --- | --- | --- |
| Lexical v1 | 22/30 | .929 | 6 | 10/12 | .909 | 1 |
| Lexical v2 | 23/30 | .964 | 8 | 10/12 | .909 | 1 |
| Lexical v3（10-21基线） | 30/30 | 1 | 0 | 10/12 | .909 | 1 |
| Hybrid RAG | 30/30 | 1 | 0 | 12/12 | 1 | 0 |

“严格通过”要求全部期望片段且不混入禁止片段，负例要求无命中；Recall仅按正例的标注片段统计。提示预算保持3段/1500字符。两组均已查看，是回归数据，不是独立holdout，更不是LLM回答准确率。首版未限制向量候选时开发仅27/30、改写11/12，暴露噪声；随后按通用字段和业务概念准入收紧，未修改标签。原始与最终测量均保留于 `docs/evals/`，含语料/数据集SHA-256。42条冻结案例同时进入pytest。

## MCP协议与权限

FastAPI `/mcp` 实现JSON-RPC 2.0、无状态Streamable HTTP的JSON响应子集。支持initialize、ping、tools/list、tools/call、resources/list、resources/templates/list、resources/read与通知202；不提供SSE订阅、会话恢复或server-initiated请求。GET流无需使用，不将普通REST包装声称为完整MCP全特性。

五项工具：`query-stock-level`、`query-sales`、`suggest-replenish`、`read-form-record`、`read-form-run`。Resource模板为 `supply://stock/{sku}`、`supply://sales/{sku}/{days}`、`supply://forms/records/{recordId}`。补货实际调用Java `ReplenishRules`：AUD-001目标90、缺口72、整箱72。

每个RPC用当前Bearer经Java发现接口重新验证；固定工具/路径、JSON Schema校验、请求长度、Origin校验，禁止任意代理URL、自动重定向及写工具。读取表单仍使用独立reader身份，不让运营JWT成为审批身份。

```sh
# 完整本地服务启动后
python scripts/agent-upgrade-smoke.py
# 可选官方MCP SDK互通检查，Node22+
cd scripts/mcp-validation
npm ci
node smoke.mjs
```

官方 `@modelcontextprotocol/sdk@1.30.0` 的StreamableHTTPClientTransport已实测发现5工具、3Resource模板、读取库存及补货，工具调用落到真实Java。

## Trace与上下文

工作台问答旁 `AgentTracePanel.vue` 每300ms读取本次运行。Java `/api/agent/runs/{id}` 及 `/cancel` 代理FastAPI trace。展示轮次、工具schema、阶段耗时、停因与 `ok/degraded/error/cancelled`，取消使用实际协程取消。

Prompt分层只展示system/history/reference/user的条数、字符数和引用ID，reference说明隔离的 `<参考文档>`，不返回完整系统提示、历史正文、工具值、Bearer或隐藏推理。最多100运行、TTL15分钟，按Bearer哈希+requestId隔离；同用户刷新登录令牌也不能读取旧令牌轨迹。非持久化运维APM，不能据此声称分布式追踪已落地。

## 重置演示

顶部管理员“重置演示数据”明确确认后重置两个独立服务，保留账号、会话和审计；清除当前浏览器旧采购恢复请求，重新挂载页面。MySQL必须为loopback `supply_agent` 且带 `db/06_demo_reset_guard.sql` 合成标记；可用 `python scripts/demo.py enable-reset` 只启用标记，不清表。运营seed在单事务执行DML，不执行DDL。表单仅允许内存H2、显式开启flag及独立服务端reset key；重置与进行中的HTTP请求互斥。reset不暴露给Agent/MCP。

MySQL和表单分别提交，页面如实显示部分成功，不能宣称跨库原子事务。`FLOW_DEMO_RESET_*` 只用于本机演示，不是生产凭据。

## 最新复验

运营Java280、表单Java32、Python407通过/3跳过、Vue151、Pi19均通过，Vue生产构建与Pi类型检查通过。旧真实HTTP27及Pi7场景73项、原浏览器流程保持通过；新增页面5组、Java/MCP HTTP6组、官方SDK互通通过。测试各自覆盖不同层，不合计为“模型成功率”。运行 `python scripts/verify.py` 可复验离线门禁及消融。
