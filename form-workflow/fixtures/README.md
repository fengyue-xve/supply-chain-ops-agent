# fixtures — 脱敏本地夹具

全部数据虚构，与任何真实业务/公司数据无关。files 由 Java 服务与 Pi Agent 两侧**共同读取**（同一份事实）。

| 文件 | 内容 |
|---|---|
| `records.json` | 7 条脱敏预装单记录（含故障注入配置） |
| `rules.json` | 外箱唛规则表（服务端真值：mark_rule → 拟写值 + 规则来源） |
| `events.json` | 7 条流转事件（含旧版本事件） |
| `users.json` | demo 身份夹具（token → actor_id/role） |

## 记录与场景对应

| 记录 | 版本 | 规则 | 用途 |
|---|---|---|---|
| R-1001 | v3 | RULE_BOX_A → `LZL04` | happy path |
| R-1002 | v1 | RULE_BOX_B → `BA99` | happy path 2（多记录） |
| R-1003 | v2 | RULE_BOX_A（当前值已是 LZL04） | 同值回写 no-op |
| R-8001 | v7 | RULE_BOX_B | 旧版本事件（event v6 < 7 → `STALE_EVENT`） |
| R-9002 | v4 | RULE_BOX_A | `WRITE_FAIL_ONCE`：首跑失败 → MANUAL_REVIEW；replay 成功 |
| R-9003 | v5 | RULE_BOX_B | `WRITE_FAIL_ALWAYS`：replay 仍失败，保持 MANUAL_REVIEW |
| R-9004 | v6 | RULE_BOX_B | `WRITE_UNCERTAIN`：写入已生效但响应不确定 → MANUAL_REVIEW；replay 按幂等键回查 → 同值 `SKIPPED` → SUCCEEDED（零新增写入） |

## 故障注入语义（本地表单适配器模拟，仅 demo）

| kind | 行为 |
|---|---|
| `WRITE_FAIL_ONCE` | 下一次写入尝试抛确定性失败，`remaining` 归 0 后恢复 |
| `WRITE_FAIL_ALWAYS` | 每次写入尝试均失败 |
| `WRITE_UNCERTAIN` | 写入**实际生效**但调用方得到"结果不确定"，`remaining` 归 0 后恢复；重放时靠幂等键回查对账 |

## 场景清单（Phase 1 由 Java 集成测试覆盖；Phase 3 由跨进程 E2E 覆盖）

1. happy path：submit → plan → approve → apply → SUCCEEDED（写入 1 次）
2. 未批准零写入：submit → plan 后直接 apply → 409，写入 0 次
3. 幂等复跑：SUCCEEDED 后再次 apply → `SKIPPED`，写入不增
4. 同值回写：R-1003 → SUCCEEDED + `SKIPPED`，写入 0 次
5. 非法迁移 / operator 越权批准 → 拒绝且零写入
6. 失败注入：R-9002/9003/9004 → MANUAL_REVIEW，不虚报成功
7. 失败重放：R-9002 replay 成功；成功项（R-1001）不受影响
8. 旧版本事件：R-8001 → `STALE_EVENT`，记录不被覆盖
9. 重复事件（同 event_id）→ 去重返回既有 run
