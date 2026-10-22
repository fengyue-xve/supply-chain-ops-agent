# Architecture

~~~mermaid
flowchart TB
  Query[运营问题] --> RAG[Lexical + Vector]
  RAG --> RRF[RRF k=60]
  RRF --> Prompt[参考文档区块]
  Prompt --> Agent[受限 Agent Loop]
  Agent --> MCP[MCP JSON-RPC 2.0]
  MCP --> Java[库存 / 补货 / 表单只读工具]
  Agent --> Trace[Trace Event Stream]
  Trace --> UI[Vue AgentTracePanel]
  Java --> DB[(MySQL)]
~~~

## Hybrid retrieval

词法层执行别名归一、泛词停用、标题分权与特征词准入；向量层使用确定性特征向量。两路结果以 RRF 融合，评测脚本输出开发集和同义改写集的严格通过率与 Recall@3。

## MCP surface

MCP 使用 Streamable HTTP，提供库存销量、补货试算、表单状态等只读工具及 Resource 模板。每个 JSON-RPC 请求校验令牌、Origin、参数 Schema 和版本。

## Trace surface

每次运行记录路由、Prompt 四层、工具调用、耗时、校验结果与停因，按凭证哈希隔离后推送到工作台。

## Reset flow

重置入口恢复种子数据、清理待确认草稿并保留可审计事件，使旧凭证不能继续写入。

## Verification

使用 scripts/verify.py、scripts/e2e.py、scripts/browser_smoke.mjs、scripts/agent-upgrade-smoke.py 与 MCP SDK smoke 共同覆盖主链路。
