# Supply Chain Ops Agent

## 1. 项目定位与核心架构

供应链运营决策与采购表单流转 Agent 系统，覆盖库存销量查询、确定性补货试算、采购意向生成、表单只读核验与受控写入。

~~~mermaid
flowchart LR
  V[Vue 工作台] --> A[FastAPI Agent]
  A --> R[Hybrid RAG + RRF]
  A --> M[MCP JSON-RPC 2.0]
  A --> J[Java 业务服务]
  J --> DB[(MySQL)]
  J --> FR[表单 Reader]
  A --> T[Trace 面板]
  T --> V
~~~

## 2. 核心 AI Agent 与受控工程特性

Hybrid RAG 将四层词法规则与 512 维特征向量按 RRF（k=60）融合，并通过消融集验证召回质量：

| Retriever | Dev strict | Dev Recall@3 | Rephrasing strict | Rephrasing Recall@3 |
| --- | ---: | ---: | ---: | ---: |
| Lexical v1 | 22/30 | 0.929 | 10/12 | 0.909 |
| Lexical v2 | 23/30 | 0.964 | 10/12 | 0.909 |
| Lexical v3 | 30/30 | 1.000 | 10/12 | 0.909 |
| Hybrid RAG | 30/30 | 1.000 | 12/12 | 1.000 |

- 以 Streamable HTTP 暴露 MCP JSON-RPC 2.0：5 个只读工具与 3 个 Resource 模板，统一校验 Bearer 与 Origin。
- ReplenishRules 负责安全库存、提前期、保护期和整箱箱规计算，模型只生成待确认采购草稿。
- 采购写入使用 SKU 固定顺序行锁、pending 冲突检查和幂等键，避免跨用户重复建单。
- Agent Trace 面板展示受限轮次、Prompt 分层、工具 Schema、耗时和停因状态；顶部支持重置演示数据。

## 3. 工作台界面截图

![供应链工作台](docs/assets/dashboard.png)

![移动端工作台](docs/assets/dashboard-mobile.png)

## 4. 核心源码导航

| 模块 | 位置 |
| --- | --- |
| Hybrid RAG | [fastapi-service/app/rag/hybrid.py](fastapi-service/app/rag/hybrid.py) |
| 消融评测 | [fastapi-service/evals/hybrid_ablation.py](fastapi-service/evals/hybrid_ablation.py) |
| MCP 端点 | [fastapi-service/app/api/mcp.py](fastapi-service/app/api/mcp.py) |
| 运行轨迹 | [fastapi-service/app/agent/live_trace.py](fastapi-service/app/agent/live_trace.py) |
| Trace 面板 | [web-app/src/components/AgentTracePanel.vue](web-app/src/components/AgentTracePanel.vue) |
| 补货规则 | [java-app/src/main/java/dev/portfolio/supply/ReplenishRules.java](java-app/src/main/java/dev/portfolio/supply/ReplenishRules.java) |
| 采购写入 | [java-app/src/main/java/dev/portfolio/supply/PoWriteService.java](java-app/src/main/java/dev/portfolio/supply/PoWriteService.java) |

## 5. 本地快速启动与自动化验证

~~~powershell
python scripts/demo.py prepare
python scripts/demo.py init-db
python scripts/demo.py start
python scripts/verify.py
python scripts/e2e.py
node scripts/browser_smoke.mjs
python scripts/agent-upgrade-smoke.py
python fastapi-service/evals/hybrid_ablation.py
~~~

MCP 官方 SDK smoke 与完整矩阵见 [docs/architecture.md](docs/architecture.md)。
