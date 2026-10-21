# 供应链运营与发货准备核验 Agent

面向库存运营人员的个人演示项目：查询可信库存与销量 → 解释补货依据 → 人工复核采购意向 → 独立查看发货准备表单的执行状态与回填结果。使用合成商品、订单和身份，不连接企业系统。

本项目承接实习中对「自动化任务可靠执行」的思考，二次开发重点是业务规则、人工决策边界、幂等和失败恢复。它不是实习系统的源码公开版本。

## 可以实际演示什么

- Vue 工作台查询库存、销量、补货建议；Java 从 MySQL 返回数据和计算依据，模型不计算业务数量。
- 补货结合安全库存、提前期和箱规，展示固定快照日期、原始缺口、整箱量及数据不足/需复核状态。
- 管理员人工带入、修改、复核后创建 `pending` 采购意向，再核对单号独立确认。浏览器在发请求前持久化幂等键和原始内容；结果未知时锁定内容，以原键恢复。
- Java 在同一事务中锁定 SKU、检查既有待处理意向，拦截跨用户/跨幂等键的重复待处理采购；批量冲突整体回滚。
- 通过独立 reader 身份和固定 HTTP 路径查询表单服务；Pi SDK 驱动审批、受控回填、回查及故障演示，写入规则和授权保留在 Java。
- Agent 支持工具调用、知识引用和会话；默认启动脚本明确选择离线 `demo` 提供器，另有 DeepSeek 接入。未完成真实模型效果验证。

**采购确认不增加库存、不向供应商发送订单，也不创建发货表单。外箱唛核验完成不表示已出库或已签收。** 待处理去重仅覆盖 `pending` 阶段；已确认后的在途量管理尚未实现。

## 实际演示

![本地合成数据演示](docs/assets/dashboard.png)

截图来自真实本地服务；对话使用明确选择的离线规则提供器，业务数量由Java计算。

## 启动与验证

依赖 Java 21、Maven 3.9、Node >=22.19、Python 3.13、MySQL 8.0。

```sh
python -m venv .venv
# Windows: .venv\Scripts\activate ; Linux/macOS: source .venv/bin/activate
python -m pip install -r fastapi-service/requirements.txt
cp .env.example .env
# 准备独立合成数据库；可选 docker compose up -d --wait mysql
python scripts/demo.py prepare
python scripts/demo.py init-db
python scripts/demo.py start
```

浏览器打开 http://127.0.0.1:15173 ，合成账号 `admin/admin123`、`viewer/viewer123`。停止：`python scripts/demo.py stop`。

`init-db` 会写入并更新种子数据，仅能指向独立演示库。禁止用于真实业务库。Docker 配置是可选依赖便捷方式；本机交付验证使用已有 MySQL，未验证 Docker 实际启动。

```sh
python scripts/verify.py
# 启动完整服务后，首次运行使用全新表单服务和未处理 DEM-001
python scripts/e2e.py
node scripts/browser_smoke.mjs
```

## 代码与讲解

| 目录 | 职责 |
| --- | --- |
| `java-app` | 可信工具、补货规则、JWT、事务写入、审计、表单只读适配 |
| `fastapi-service` | Agent 编排、提供器、词法知识检索、离线评测 |
| `web-app` | 查询、人工采购复核、未知结果恢复和表单查看 |
| `form-workflow` | 独立 H2 表单执行服务、Pi SDK 适配器与合成场景 |
| `db` / `contracts` | 合成数据库与工具契约快照 |

建议依次阅读：[业务链路](docs/01-business-flow.md)、[运行演示](docs/02-run-demo.md)、[代码导读](docs/03-code-tour.md)、[验证与局限](docs/04-verification.md)、[来源与边界](docs/05-provenance.md)、[面试讲解](docs/06-interview.md)。

当前词法检索规则集 30/30 通过，另保留一组改写检查 10/12，失败项公开保留。两者都不是模型回答准确率；早期冻结语料回归与当前业务评测分开报告。详见验证文档。

界面布局参考与本轮视觉验收见 [界面说明](docs/07-interface-design.md)。
