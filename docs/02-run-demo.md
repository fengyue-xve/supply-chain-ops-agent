# 独立运行与演示

## 环境准备

使用 Java21、Maven3.9、Node>=22.19、Python3.13、MySQL8.0。命令均在仓库根目录运行，依赖可通过 PATH 找到。先创建并激活 `.venv`，安装 `fastapi-service/requirements.txt`，复制 `.env.example` 到 `.env`。

可选：`docker compose up -d --wait mysql` 会在127.0.0.1:13306建立演示库和独立测试库。已有MySQL则自行创建 `supply_agent`、`supply_agent_test` 两个独立库并授权，修改 `.env` 中端口和凭据。不要将凭据提交 Git。测试会重置测试库，演示初始化会更新种子数据，均不得指向真实业务库。

```sh
python scripts/demo.py prepare
python scripts/demo.py init-db
python scripts/demo.py start
```

启动顺序：表单18183、Java18180、Agent18181、Vue15173；均绑定127.0.0.1。日志在忽略提交的 `runtime/`。脚本检查端口冲突并记录自有进程的 PID 与创建时间，停止时核对身份。

```sh
python scripts/demo.py stop
```

## 五分钟业务演示

1. 以admin/admin123登录；查询AUD-001，7日、总覆盖14日，查看建议72和固定快照日期。带入采购表并复核，服务端409返回已有96件pending单；说明「先检查是否已经在处理」。
2. 人工开始新意向，查询DEM-001，建议48。带入、核对箱规、复核、提交，再从待确认列表核对单号和48件，独立确认。库存仍为5，没有产生入库和发货表单。
3. Agent输入「DEM-001 补货」。默认demo是规则脚本驱动工具调用；实际查询由Java返回。不能将这一演示称为真实模型效果。
4. 表单只读面板查询R-1001。运行下面的Pi演示后，从 `demo-summary.json` 取真实运行号，在面板查询执行状态、回填次数与回查次数；审批轨迹详见脱敏的Pi运行记录。采购确认不会触发这些写入。
5. 以viewer/viewer123登录，可查询事实与表单，没有采购写入口；服务端也独立返回403。

全新演示库且表单刚启动时，可用 `python scripts/e2e.py` 执行完整跨服务检查，包含全部Pi故障场景。该检查会确认DEM-001并消耗一次性表单故障计数，因此不是无状态重复命令。重复时先停止服务、重启全新表单；采购演示可在已确认后手动开始新意向，不要为了演示删除既有业务单。

```sh
# e2e之后可直接截图及查询；不再创建采购单
node scripts/browser_smoke.mjs
```

截图位于 `artifacts/browser/`；HTTP检查摘要与Pi轨迹位于 `artifacts/e2e/`。不要提交包含真实凭据的运行日志。

## 可选真实模型

停止服务，在 `.env` 设置 `AGENT_PROVIDER=deepseek` 与自己的 `DEEPSEEK_API_KEY`，再启动。请求可能产生模型费用，禁止将密钥交给前端。`/agent/health` 返回实际provider名称。项目尚未完成真实模型效果和成本评估。Pi子模块仍使用Faux脚本提供器，修改运营Agent提供器不会改变这一点。

## 回归

`python scripts/verify.py` 使用独立 `supply_agent_test` 库，执行两套Java、Python、Vue、Pi测试与构建；读取 `.env`，环境变量优先。自定义测试库可通过 `SUPPLY_TEST_DB_URL` 指定，禁止与演示库相同。Windows和Linux均用激活的虚拟环境运行；不要同时启动两个共享测试库的验证任务。
