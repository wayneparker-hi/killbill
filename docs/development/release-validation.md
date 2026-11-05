# 本地发布验收

仓库入口：`python3 tools/validation/validate.py`。复用已有 Docker PostgreSQL 和 Redis，不创建或停止基础设施容器。

## 前置与运行

- macOS/Linux，JDK 21、Maven 3.6.3+、Python 3.9+、Docker CLI；依赖准备见 [构建环境](build-environment.md)。
- 默认容器：`dev-shared-postgres`、`dev-shared-redis`；PostgreSQL 对外映射 `127.0.0.1:5432`。
- 本机默认测试用户/密码为 `dev`；可用 `--db-user` 和环境变量 `KB_VALIDATION_DB_PASSWORD` 覆盖。
- 初始化 PostgreSQL 兼容 DDL 的事件触发器需要当前用户具备现有管理员权限；脚本不修改集群角色。

```bash
python3 tools/validation/validate.py
```

macOS 自动通过 `/usr/libexec/java_home -v 21` 选择 JDK，绕开继承的 JDK 8；Maven 使用本机真实路径。其他机器可显式传入：

```bash
python3 tools/validation/validate.py \
  --java-home /path/to/jdk-21 --maven /path/to/maven/bin/mvn \
  --postgres-container dev-shared-postgres --redis-container dev-shared-redis
```

默认执行全量 `clean install -DskipTests=true`。若已经完成同一份代码的全量构建，可用 `--skip-build`；报告会明确标为跳过，不能据此宣称本次完成了全量构建。

## 实际执行范围

| 阶段 | 检查 |
|---|---|
| 基础设施 | PostgreSQL 版本、Redis PING；不读写 Redis 业务键，不启用 Redis 缓存/时钟 |
| 构建 | reactor 全量构建与插件 ABI；七项静态检查按项目规约跳过，构建阶段跳过测试 |
| 数据库回归 | 全新数据库，账户（2 项）、业务集成（1 项）、队列停机（3 项）、`ITPostgresqlDurableEvents`（4 项）及双节点 IT（2 项），共 12 项；不同夹具使用独立库 |
| 启动 | 另一个全新数据库，部署实际 WAR/web.xml/生产 Guice 装配；只监听 `127.0.0.1` 的随机端口 |
| 插件与业务 | 从构建产物安装 hello-world；创建租户/账户/支付方式，standard-monthly EVERGREEN 订阅生成发票，手动通过测试插件支付 |
| 恢复 | 停服后用同一数据库和插件目录再次启动，验证插件 RUNNING、订阅 ACTIVE、发票金额/COMMITTED/余额为零以及唯一 SUCCESS/PURCHASE 交易 |
| 停机 | 两次停服日志不得有队列 timeout 或未完成记录告警；出现则验收失败并保留数据库 |

测试使用 `postgresql` profile；`travis` profile 强制 H2，不能用于这组 PostgreSQL 验收。DB 参数经 `-DsurefireArgLine` 传入，并保留英文 locale、四个 add-opens。现有测试 DB helper 固定 localhost:5432，脚本拒绝用其他地址运行这组 slow 测试。不同模块顺序执行。

HTTP 冒烟使用生产模式（`server.test.mode=false`），没有替换 Guice 模块或模拟 HTTP 响应。支付通过已有本地成功型 hello-world 插件，不调用外部支付网关；插件交易内存的持久化不在本轮保证范围。发票列表需 `includeInvoiceComponents=true` 才能按实际金额/余额判断应付账单。

多个逻辑通知队列共享调度器，非最后一个队列停止返回成功，只代表逻辑停用；最后一个停止才等待 handler 和完成写库。正常排空后重启的已完成通知不重复；超时后过期的 IN_PROCESSING 记录会由 reaper 恢复，handler 的副作用可能重复，业务仍需幂等。

durableEvents 使用真实 `bus_ext_events` 表和生产 adapter/DAO/reaper。子 JVM 分别在发布成功后、UUID 幂等副作用提交后且确认前调用 halt；新 JVM 验证 UUID/topic/payload 不变及恢复消费。恢复过程会先留下 REAPED 历史，再生成 PROCESSED；同 UUID 多条历史不能直接视为重复副作用。测试还覆盖瞬时/永久失败、缺失订阅和真实 PostgreSQL 表锁超时。IT 类由验收入口显式选择，默认离线回归不连接 PostgreSQL。不会停止数据库或修改集群角色。实测结果与变异证据见 [durableEvents 验证](../../changes/archive/durable-events-postgresql/validation.md)。

双节点 IT 使用同机两个独立 JVM、不同 `CreatorName`、相同 JVM 时区和同一个独立 PostgreSQL 库，通过屏障观察两节点同时在途且 claim 有效。正常场景验证每条消息一次投递；故障场景强制终止一个确认前节点（Unix 退出码 137），存活节点不重启，等待生产 reaper 恢复同 UUID。数据库 owner、REAPED/PROCESSED、每节点投递日志及 UUID 唯一业务结果共同校验。它验证的是生产 adapter/队列，未启动两个完整 WAR，也不覆盖跨主机网络分区、租约过期后旧 worker 继续执行、数据库故障或公平分配。实测与测试时区问题见 [双节点验证](../../changes/archive/durable-events-dual-node/validation.md)。

## 输出与清理

默认输出在 `var/release-validation/<时间>-<随机后缀>/`，不进入 Git。`--output` 可以指定新的目录；完整构建时不能放在仓库的 Maven target 目录中，以免 clean 删除报告。

- `report.json`：阶段、命令、退出码、构建/ABI 数量、实际测试数量、库名、DDL/产物摘要和日志路径；不含数据库密码。
- `build.log`、`postgres-*.log`、`server-1.log`、`server-2.log`：原始结果。输出目录权限 0700；临时配置位于其中，不提交或作为 CI 制品上传。
- `durable-events-*/`：子 JVM 日志、退出码对应记录及投递观察；私有 `connection.properties` 不作为 CI 制品上传。子 JVM halt 退出码 23/24 是测试预期的故障点，父测试仍须完整通过。
- 正常成功：停止两次启动的本次 Java 进程，并删除本次创建的数据库；原有数据库、容器和 Redis 数据不清理。
- 失败、超时或中断：非零退出，停止本次服务；保留库名和日志便于诊断。Maven/Java 子进程按进程组终止，超时后强杀；服务被强杀会将验收标为失败。
- `--keep-databases`：成功也保留本次库。确认诊断完成后，可对报告列出的精确库名手动 `dropdb`；不要批量按前缀删除其他运行中的验收库。

## 守卫与 CI

```bash
python3 -m unittest discover -s tools/validation -p 'test_*.py' -v
```

守卫覆盖越权清库、DDL 错误保留、子命令失败/超时、空测试/跳过/错误后端、缺失插件、错误交易/账单状态及停机队列异常。初版守卫验证见 [发布验收记录](../../changes/archive/lpr-release-validation/validation.md)；队列修复记录见 [队列停机变更](../../changes/archive/queue-shutdown-drain/validation.md)。

GitHub workflow 在现有全量构建及运行时回归之后，复用 job 的 PostgreSQL 16 / Redis 7 services 执行同一入口（`--skip-build`），上传 JSON 报告与日志。配置了 Linux 验收入口，不等于本机已执行过远端 CI。

本轮不覆盖 MySQL、真实多机/NFS、进程外插件生产化、Redis 缓存兼容性或全项目 slow/DB 测试。
