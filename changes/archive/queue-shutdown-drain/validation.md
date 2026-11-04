# 队列停机验证记录

## 范围与根因

- 基线 `6c70d64`，工作区初始干净。生产修改仅 `NotificationQueueDispatcher` 和 `DefaultQueueLifecycle`，未变更公共 API/POM、bind/@Provides、插件生命周期或默认平台装配。
- 原 WAR 两次停机的 8 条 timeout 均在数毫秒内出现。非最后一个逻辑队列停止返回 false，Core 调用方将其解释为超时；最后一个队列实际上才关闭共享调度器。
- 完成线程收到停止标记后直接退出；门闩控制当前批次，停止后排队的完成记录或第二批重试记录未被排空。修复停止结果及循环退出条件。

## 命令与结果

```bash
python3 tools/validation/validate.py --output /tmp/killbill-queue-final
python3 -m unittest discover -s tools/validation -p 'test_*.py' -v
```

Java 验证统一使用 JDK 21.0.11、真实 Maven 3.6.3，七项静态检查按 AGENTS 跳过，fork 保留英文 locale 与四个 add-opens。

| 检查 | 结果 | 日志 / 证据 |
|---|---|---|
| 修改前全量基线 | 95 reactor / 22 ABI，退出 0 | `/tmp/killbill-queue-baseline.log` |
| 修复前定向测试 | 5 项，3 failures：逻辑停用 false、完成 1/41、重试 40/45 | `/tmp/killbill-queue-red.log` |
| 修复后定向测试 | 5 项，0 failure/error/skip | `/tmp/killbill-queue-green.log` |
| 最终全量构建 | 95 reactor / 22 ABI，通过，构建跳过测试 | `/tmp/killbill-queue-final/build.log` |
| PostgreSQL 账户 / 业务 | 2 + 1 项，0 failure/error/skip | 同目录 `postgres-account.log`、`postgres-business.log` |
| PostgreSQL 停机与恢复 | 3 项，0 failure/error/skip，独立数据库 | 同目录 `postgres-queue-shutdown.log` |
| 真实 WAR 业务与恢复 | 发票 100.00 USD，成功支付，重启后余额 0 / 单次成功交易 | 同目录 `report.json` |
| 两次真实 WAR 停机 | 两项 queue-shutdown passed，无队列 timeout / 未排空告警 | 同目录 `server-1.log`、`server-2.log` |
| Python 守卫 | 8 项通过 | `/tmp/killbill-queue-python.log` |
| 变异守卫 | 8 项检出并还原 | `/tmp/killbill-queue-mutants.json` |
| H2 支持范围 | 34 项，0 failure/error/skip，显式排除硬编码 MySQL 的 Demo | `/tmp/killbill-queue-regression-h2-supported.log` |
| 运行时 / 插件 / REST | 241 + 60 + 6 = 307 项，0 failure/error/skip | `/tmp/killbill-queue-regression-runtime.log`、`-adapters.log`、`-rest.log` |
| 资源清理 | 本次 3 个数据库已删除，未强杀服务；共享容器保持运行、Redis PONG | 最终 `report.json` retained_databases=[] |

## 变异验证

每次只改变一项保护条件，运行对应测试并还原；源文件均由 finally 恢复。Java 变异不安装制品，最终验收重新全量 clean install。

| 变异 | 对应守卫 | 结果 |
|---|---|---|
| logical-stop：恢复非最后一个队列返回 false | `testStoppingOneSharedQueueLeavesTheOtherRunning` | 检出 |
| completed-drain：移除完成队列为空的退出条件 | `testShutdownDrainsEveryCompletedBatch` | 检出 |
| retry-drain：移除重试队列为空的退出条件 | `testShutdownDrainsRetriesAfterTheCurrentCompletionBatch` | 检出 |
| real-timeout：忽略 handler 关闭失败 | `testRealTimeoutIsReportedAndExpiredClaimIsRedelivered` | 检出 |
| handler-wait：将等待改为 0ms | `testInflightCompletionAndPendingNotificationSurviveRestartWithoutDuplicates` | 检出 |
| timeout-log：不识别 timeout 日志 | Python 停机守卫 | 检出 |
| pending-log：不识别未完成记录日志 | Python 停机守卫 | 检出 |
| log-gate-wiring：移除 stop_server 中的检查调用 | Python 停机守卫 | 检出 |

handler-wait 初次变异直接返回 true 导致 InterruptedException catch 编译失败，不计作守卫检出；最终使用可编译的 0ms awaitTermination，确认测试超时失败。日志 `/tmp/killbill-queue-mutant-handler-wait.log`。

## 环境失败与对照

- 默认 `mvn -pl killbill-commons/queue test`：66 项，9 failures / 46 skips；9 项失败均为旧测试夹具缺少 `/mysql-Mac_OS_X-aarch64.tar.gz`，发生在 beforeClass。日志 `/tmp/killbill-queue-fast.log`，命令退出 1，不计为通过。
- 同命令、同环境、同测试，仅将两个生产源文件临时恢复为 HEAD 内容后，仍有相同 9 项初始化失败；新增 3 项行为守卫也按预期失败。源文件 finally 恢复。日志 `/tmp/killbill-queue-default-control.log`。
- `-Ptravis` H2 扩大回归：38 项，1 failure / 3 skips；唯一初始化失败来自 `TestPersistentBusDemo` 自行硬编码 MySQLEmbeddedDB，未使用 profile 对应的 TestSetup。日志 `/tmp/killbill-queue-regression-h2.log`，退出 1。随后显式排除该 Demo 重跑，其覆盖仍不计作通过。
- 支持范围命令：`mvn -B -ff -pl killbill-commons/queue test -Ptravis '-Dtest=!TestPersistentBusDemo'`，附上述静态跳过项和 surefireArgLine，34 项通过。未修改测试夹具或测试后端选择逻辑。
- 307 项回归使用 `.github/workflows/ci.yml` 中原有三组 Java 回归的模块、测试选择及 JVM 参数；逐命令退出 0。本机执行结果不代表已运行远端 CI。

## 语义与边界

- 非最后一个队列停止成功只代表逻辑停用；共享 handler 与完成线程在最后一个停止时排空。没有修改 Core 服务关闭顺序。
- 正常停机时在途 handler 完成后写历史；重启仅执行新待处理通知，已完成通知未重复。
- 真实超时后 IN_PROCESSING 保留；测试等待原 handler 退出再推进时钟，由新 service 的生产 reaper/DAO 恢复。共观察到两次 handler 调用，明确属于至少一次；业务副作用仍需幂等，不承诺 exactly-once。
- PostgreSQL 超时恢复使用同 JVM 的新 service；真实 WAR 另验证进程重启的业务持久化，不等同于在任意强杀点验证通知副作用。
- 日志门禁识别两类已知队列关闭告警，不代表能识别任意未来错误日志。MySQL、任意阻塞 handler 的强制终止、真实多节点/NFS、Redis 缓存/时钟与远端 Linux CI 未验收。
