# 发货准备表单子模块

独立Java/H2服务负责规则、计划、人工审批、受控外箱唛回填与回查，Pi运行时使用实际Pi SDK和Faux脚本提供器。全部是合成数据和角色token，不是真实飞书连接，也不代表物流履约。

运营Java通过专用 `token-reader-1` 调用固定GET接口，只展示字段白名单，不转发用户JWT。reader不能批准、apply或replay；运营页面没有写入代理。

在仓库根使用 `python scripts/demo.py prepare/start` 启动，`python scripts/e2e.py` 执行Pi全部场景。运行号写入 `artifacts/e2e/pi/demo-summary.json`，可以在Vue表单面板通过运营API查询。H2重启会重置所有表单状态，一次性故障场景需要全新实例。

`scripts/verify.ps1` 是旧的Windows单模块验证入口，默认8081；新交付统一推荐根目录脚本，避免与其他项目端口冲突。
