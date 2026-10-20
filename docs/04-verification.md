# 验证范围与已知局限

验证使用本地MySQL8.0、Java21、Node、Python虚拟环境和合成数据；最终结果以仓库CI及本地验证摘要为准。脚本将测试日志保留在 `artifacts/verify/`，不纳入源代码提交。

- Java运营服务：补货公式、数据不足/复核、JWT/RBAC、采购幂等与跨键pending冲突、事务/并发、表单只读适配及错误映射。
- Java表单服务：审批边界、写入和回查幂等、故障恢复、reader只读限制。
- Vue：查询与采购交互、未知响应持久化、角色入口、表单查询及构建。
- Python：编排、schema、工具响应、知识引用、历史与离线提供器。
- Pi：类型检查、离线测试，另由 `scripts/e2e.py` 对全新Java表单服务执行真实HTTP场景（提供器为Faux）。

## 2026-10-20 本地实测

运营Java274、表单Java29、Python344通过（另3项Windows符号链接权限检查跳过）、Vue149、Pi Node19；两套前端/后端构建与Pi类型检查通过。真实HTTP检查27项，Pi跨进程7场景73项检查通过；真实浏览器验证采购复核、创建与确认、viewer只读、表单记录及实际执行号查询，无接口mock和页面异常。另验证表单停止返回503而库存查询保持200。

可机读摘要见 [verification-2026-10-20.json](verification-2026-10-20.json)。这些结果对应合成业务场景，不是线上性能或真实模型效果报告。

## 检索评测必须分开阅读

```sh
cd fastapi-service
python -m evals.run --suite current-business.v1
python -m evals.holdout_audit --out ../artifacts/holdout.json
```

当前业务规则集30例通过30例，其中25例要求覆盖业务知识，5例要求正确拒答/无命中。这是开发过程中反复使用的词法检索规则集。

另外12例改写检查通过10例，失败保持可见：

- `leadDays超过targetCoverDays会怎样？`：只选到公式，缺少必须的参数口径章节。
- `assessmentStatus是review_required时应如何描述？`：结果混入missingSkus相关内容。

改写检查已被查看，后续不能继续声称是未见过的独立测试集。审计命令在有失败时返回1，不纳入「全绿」指标。报告保留语料清单、数据集哈希与失败明细。旧 `knowledge.v1/retrieval.v1/v2/shadow` 使用 `evals/fixtures/knowledge-before-business-20261020` 冻结语料，只证明历史行为回归，不证明当前业务或模型准确率。

## 不能据此宣称的能力

未验证真实模型任务成功率、线上延迟/成本或生产规模；未对接真实采购、物流、CRM、飞书审批或企业数据库；未实现入库、在途、预留、收货核销及端到端订单关联；无订单按0销量无法识别采集断档；表单内存H2与合成token不适合生产。Docker只提供可选配置，未在交付机器运行验证。
