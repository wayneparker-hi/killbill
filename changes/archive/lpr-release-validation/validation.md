# 发布验收验证记录

## 范围与环境

- 基线 `0d2ab2d`，初始工作区干净；新增验收工具、CI 和文档，没有修改生产 Java/POM、bind/@Provides 或默认业务实现。
- 使用现有 `dev-shared-postgres`（PostgreSQL 16.14 / aarch64 Linux 容器）和 `dev-shared-redis`（Redis 7），宿主 macOS / JDK 21.0.11 / Maven 3.6.3。
- 独立基线全量构建 95 reactor 项目 / 22 ABI 通过：`/tmp/killbill-release-baseline.log`。
- 真实 WAR、生产 web.xml/Guice、生产模式、POLLING 队列及 hello-world LPR 测试支付插件。HTTP 仅监听 127.0.0.1 随机端口；Redis 仅 PING。

## 最终命令与结果

```bash
python3 tools/validation/validate.py --output /tmp/killbill-release-final
python3 -m unittest discover -s tools/validation -p 'test_*.py' -v
```

| 检查 | 结果 | 证据 |
|---|---|---|
| 验收入口 | 退出 0，report.status=passed | `/tmp/killbill-release-final.log`、`/tmp/killbill-release-final/report.json` |
| 全量 clean install | 95 reactor / 22 ABI，通过；构建阶段跳过测试 | `build.log`（同输出目录） |
| PostgreSQL 账户回归 | 2 项，0 failure/error/skip | `postgres-account.log` |
| PostgreSQL 业务集成 | 1 项，0 failure/error/skip | `postgres-business.log` |
| 真实 HTTP 业务 | 租户/账户/支付方式/ACTIVE 订阅，发票 100.00 USD，单次 SUCCESS/PURCHASE，余额 0 | report 的 business-flow 阶段、`server-1.log` |
| 重启恢复 | 同库同插件目录，插件 RUNNING、订阅 ACTIVE、发票 COMMITTED/金额不变/余额 0、唯一成功交易 | report 的 restart-recovery 阶段、`server-2.log` |
| Python 失败守卫 | 7 项全部通过 | `/tmp/killbill-release-final-guards.log` |
| 变异守卫 | 17 项全部检出，临时修改已还原 | `/tmp/killbill-release-mutants.json` |
| 清理 | 两个最终验收库自动删除；服务无强杀；诊断库 5 个另行精确删除；现有容器仍在，Redis PONG | report、`/tmp/killbill-release-diagnostic-cleanup.json` |
| 工作流与文档 | YAML 解析、局部 Markdown 链接、Python 编译和函数长度、git diff --check | 提交前检查 |

两个子服务使用 SIGTERM 正常退出（143），均在预算内结束，没有 SIGKILL。最终验收运行过程中补充的命令 PID 记录和 SIGTERM 处理由独立实进程探针与最终 Python 守卫验证。

## 负例验证

每项只改变一个保护条件，观察测试/探针失败后还原；没有修改生产代码或本地 Maven 制品。前 15 项运行仓库 `ValidationGuards`，后两项使用真实 PostgreSQL/进程探针；日志 `/tmp/killbill-release-mutant-<名称>.log`。

| 保护条件 | 结果 |
|---|---|
| database-owner | 检出 |
| ddl-failure | 检出 |
| command-exit | 检出 |
| command-cleanup | 检出 |
| test-count | 检出 |
| test-skips | 检出 |
| database-backend | 检出 |
| plugin-missing | 检出 |
| plugin-running | 检出 |
| subscription-active | 检出 |
| invoice-amount | 检出 |
| invoice-status | 检出 |
| purchase-status | 检出 |
| purchase-amount | 检出 |
| purchase-count | 检出 |
| ddl-strict | 检出 |
| sigterm-cleanup | 检出 |

- 实际 DDL 错误探针：临时独立库，CREATE TABLE → 不存在函数 → CREATE TABLE。正确版本非零失败并完整回滚（0 表）；移除 ON_ERROR_STOP 后探针检出错误放行；两次探针库均删除。日志 `/tmp/killbill-release-ddl-probe.log`。
- 实际 SIGTERM 探针：在 Maven classpath 子进程启动后发送 SIGTERM，入口退出 1、报告 failed、Maven 子进程消失；移除处理后出现报告仍 running / 子进程遗留，负例检出并在探针 finally 清理。日志 `/tmp/killbill-release-signal-control-result.log`。

## 开发中失败与对照

| 命令 / 日志 | 原因与处理 | 影响 |
|---|---|---|
| 首轮入口；`/tmp/killbill-release-run1.log` | 继承 JAVA_HOME 为 JDK 8，版本守卫拒绝；macOS 默认改用 java_home -v 21 | 未创建数据库 |
| 第二轮账户测试；`/tmp/killbill-release-run2/postgres-account.log` | travis profile 强制 h2=true，与 useLocalDb 同用导致空 H2 库缺表；仅改 profile 为 postgresql，在同一临时库对照通过：`/tmp/killbill-release-postgres-control.log` | 2 failures / 1 skip；入口明确失败，没有算成 PostgreSQL 通过；生产代码未改 |
| 第三轮 HTTP；`/tmp/killbill-release-run3.log` | pluginsInfo 要求租户 header；改为创建租户后再检查插件 | 401，测试请求顺序修正 |
| 第四轮 HTTP；`/tmp/killbill-release-run4.log` | 发票列表默认不含组件，余额不能判断应付账单；DB 与日志已确认实际生成 100 USD 发票；增加 includeInvoiceComponents=true | 测试等待超时，生产发票逻辑未改 |
| 完整 HTTP 对照；`/tmp/killbill-release-http-control/report.json` | 调整查询参数后真实支付与重启恢复均通过；最终再运行含完整构建的入口 | 非全套回归，仅 HTTP 定向对照；最终结果见上表 |

失败报告仍保留；诊断结束后，仅根据本次报告中成功创建的精确库名清理 5 个临时库，没有按前缀批量删除。源码恢复后再次运行守卫；最终全量构建和 HTTP 流程通过。

## 审查与剩余边界

- 已复核数据库所有权、SQL 错误传播、进程组清理、deadline、报告原子写入、日志/配置权限、测试数量与跳过项；无新增产品网络入口或业务实现分支。
- 输出默认放 var/ 而非 target/，避免全量 clean 删除自身报告；CI 在同一 job 的全量构建后使用 --skip-build，保留跳过事实。
- SpotBugs、dependency/dependency-scope/dependency-versions、duplicate-finder、enforcer、RAT 按规约跳过；没有宣称 307 项旧运行时回归在本轮重跑、全项目 slow/DB 测试通过，或静态检查全绿。
- CI 增加 PostgreSQL 16/Redis 7 services、离线守卫、相同验收入口与报告上传；仅完成本地验证与 YAML 检查，远端 Linux CI 尚未执行。
- 未验证 MySQL、真实多机/NFS、durableEvents 的 PostgreSQL 故障恢复、Redis 缓存/时钟或进程外支付生产装配。
- 日志仍出现停服队列超时告警（例如 subscription-events、next-billing-date-queue）；本轮验证了进程退出与业务恢复，没有证明所有在途队列完成 drain，也没有将此告警判定为无害。后续应单独用在途队列/停服对照定位。
- 初次 HTTP 定向运行出现一次 PluginsInfoApi 未发布 warn；最终两次启动记录发布 24 个服务（包含该接口）。尚无单变量根因证据，不把其归类为已修复问题。
