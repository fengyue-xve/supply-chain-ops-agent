# contracts — Pi/Agent 与 Java 的契约（v1.0.0）

本目录是 **Java 服务（java-flow-service）与 Pi Agent（pi-agent-runtime）之间唯一的事实来源**。
两侧的请求、响应、状态枚举、错误码、工具参数都必须与本目录一致；两侧各有契约测试锁定一致性。

## 命名与编码

- JSON 字段一律 `snake_case`；文件 UTF-8；金额/时间无歧义（时间用 ISO-8601 `date-time`）。
- 所有示例数据均为脱敏虚构值（`R-1xxx`、`LZL04`、`BA99`、`SKU-DEMO-xx`），不含任何真实业务数据。

## 文件

| 文件 | 覆盖 |
|---|---|
| `flow-event.schema.json` | 表单流转事件（`POST /api/flow/events` 请求体） |
| `plan.schema.json` | 流转计划（`plan_transition` 请求体 / run 视图内嵌 plan） |
| `run-status.schema.json` | 运行视图（含状态枚举、apply 结果、审计条目枚举） |
| `record.schema.json` | 表单记录视图（`get_form_record` 响应） |
| `tools.schema.json` | 工具 ↔ 端点路由表（7 个 Agent 工具 + 人工动作 + 观测端点） |
| `errors.schema.json` | 错误响应形状与错误码枚举 |
| `README.md` | 本文件：状态机、错误码注册表、幂等键、校验责任 |

## 状态机（唯一状态字典）

```text
(新建) ──→ RECEIVED ──→ PLANNED ──→ WAITING_APPROVAL ──→ EXECUTING ──→ SUCCEEDED
                                                     └──→ REJECTED（终态）
EXECUTING ──→ FAILED ──→ MANUAL_REVIEW（自动登记）
MANUAL_REVIEW ──→ REPLAYING ──→ SUCCEEDED
                          └──→ FAILED ──→ MANUAL_REVIEW（自动登记）
```

迁移动作与守卫（每次迁移必须校验前置状态，非法迁移零写入）：

| 迁移 | 触发 | 前置守卫 |
|---|---|---|
| → `RECEIVED` | `POST /events`（operator） | 事件版本 == 记录版本；同 event_id 去重返回既有 run |
| `RECEIVED→PLANNED` | `plan_transition`（agent） | 计划通过校验（值匹配规则表、幂等键可复算一致） |
| `PLANNED→WAITING_APPROVAL` | `request_approval`（agent） | run 处于 PLANNED |
| `WAITING_APPROVAL→EXECUTING` | `approve`（approver） | 调用者角色 = approver |
| `WAITING_APPROVAL→REJECTED` | `reject`（approver） | reason 必填 |
| `EXECUTING→SUCCEEDED` | `apply_transition`（agent） | 存在 APPROVED 审批记录；前置条件复检通过（版本未变、值在规则表内）；同值 → 零写入 `SKIPPED` |
| `EXECUTING→FAILED` | `apply_transition` | 写入失败/结果不确定 → 记录失败原因 |
| `FAILED→MANUAL_REVIEW` | 自动 | 失败即自动登记，无人工跳过 |
| `MANUAL_REVIEW→REPLAYING` | `replay_failed`（agent） | run 处于 MANUAL_REVIEW；其他状态一律 409 |
| `REPLAYING→SUCCEEDED/FAILED` | `replay_failed` 执行体 | 与 apply 同一写入路径 + 幂等键回查对账 |

幂等语义：
- **重复 apply 已 SUCCEEDED 的 run** → 200，`outcome=SKIPPED`，零写入（`write_count` 不变）。
- **同值回写**（记录当前值 == 计划值）→ 200，`outcome=SKIPPED`，零写入，run 照常进入 SUCCEEDED。
- **重复提交同 event_id** → 200，`deduplicated=true`，返回既有 run，零写入。

## 观测计数（“未批准零触达”的证据）

`GET /api/flow/stats` 额外暴露 `apply_endpoint_hits` / `replay_endpoint_hits`：`POST /apply`、
`POST /replay` 端点在服务生命周期内被请求的次数（在角色校验**之前**计数，含被拒请求）。
未批准路径的验收证据 = 客户端预检阻断时计数不增长（零触达）＋ 直接请求被服务端
`409 INVALID_TRANSITION` 拒绝且零写入。字段与 `tools.schema.json` 的锁步断言见 Java
`ContractConsistencyTest` 与 Node 侧 `tools-contract.test.ts`。
注：`total_skip_count` 统计**收敛为 SKIPPED 的 run 数**（按 `apply_result` 计数）；
对已 SUCCEEDED run 的重复 apply 不产生新 SKIPPED 状态，故该计数不变。

## 幂等键

```text
idempotency_key = "FLOW_IDK_" + sha256_hex(source_record_id + ":" + flow_type + ":" + business_version)[:16]
```

- 不以 SKU 或任何业务字段单独作键；原始 `source_record_id` 不出现在 Trace/审计产物中（审计只落 `record_ref = sha256(source_record_id)[:12]`）。
- Node 侧与 Java 侧各自独立实现同一派生；**契约测试断言两侧对同一夹具产出同一键**。Java 在 `plan_transition` 时复算并要求与计划中的键一致（不一致 → 422 `PLAN_INVALID`）。

## 错误码注册表（`errors.schema.json` 与之同步）

| error_code | HTTP | 含义 |
|---|---|---|
| `BAD_REQUEST` | 400 | 请求体不合法（形状/必填/枚举） |
| `UNAUTHENTICATED` | 401 | 缺少或未知 token |
| `FORBIDDEN_ROLE` | 403 | 角色无权执行该动作（如 operator 批准） |
| `NOT_FOUND` | 404 | run / record 不存在 |
| `INVALID_TRANSITION` | 409 | 前置状态不满足（含越权时机的调度错误），目标字段零写入 |
| `STALE_EVENT` | 409 | 事件业务版本落后于记录当前版本（乱序/旧版本事件） |
| `VERSION_MISMATCH` | 409 | 事件业务版本高于记录当前版本（数据异常） |
| `NOT_APPROVED` | 409 | 无批准记录即申请执行（防御性，正常流程不可达） |
| `PRECONDITION_FAILED` | 409 | 执行前复检失败（版本已变、规则值不符等） |
| `PLAN_INVALID` | 422 | 计划未通过校验（幂等键不一致、值不在规则表内等） |
| `INTERNAL` | 500 | 未预期错误 |

## 校验责任分工

- **Java**：DTO + 业务校验（最强约束：规则表为服务端真值）；契约测试断言「枚举值 / 必填字段名 / 路由表」与 Schema 一致。
- **Node（Pi 侧）**：以本目录构建工具定义（参数 Schema 内嵌于 `tools.schema.json`），运行时可做完整 Schema 校验。
- **冲突处理**：任何一侧不一致以本目录为准；改契约必须同时改两侧测试。

## 审计与备注留存边界（Trace 脱敏口径）

- **审计（flow_audit / Trace 产物）**：只落结构化脱敏字段；自由文本一律只记 `*_present` 布尔。禁止出现 token、Authorization、完整 `source_record_id`（只落 `record_ref`）、完整自由文本。
- **事件备注 / 申请备注**（`submit_event.note`、`request_approval.note`）：仅用于流程触发，**不落入任何 Java 存储**。
- **审批决定备注**（`reject.reason` / `approve.note`）：属于业务决定记录，留存于 `flow_approval.note`（RunView 的 `approval.note` 可见），**不进入审计条目**。
- **写入口 fail-closed**：审计写入前经脱敏校验，命中敏感键名/值模式即抛异常（写失败优先于泄漏）。
