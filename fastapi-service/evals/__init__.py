"""W3-⑤ Agent 评测基线：33 条固定用例 + 协议级离线回放 + 指标聚合。

用法（在 fastapi-service 下）：python -m evals.run [--live] [--case-id X] [--category Y]

replay（默认）不触网、不需要任何真实凭据：Java 行为由 evals.support.JavaStub 承担，
模型行为由 evals/cassettes/agent-baseline.v1/<case_id>.json 经真实 DeepSeekProvider 解析路径回放。
报告写入 evals/reports/（已 gitignore）。
"""
