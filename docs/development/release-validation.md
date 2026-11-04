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
| 数据库回归 | 全新数据库，`TestAccountDao#testBasic+testMinimalFields`（2 项）和 `TestIntegration#testBasePlanCompleteWithBillingDayInPast`（1 项） |
| 启动 | 另一个全新数据库，部署实际 WAR/web.xml/生产 Guice 装配；只监听 `127.0.0.1` 的随机端口 |
| 插件与业务 | 从构建产物安装 hello-world；创建租户/账户/支付方式，standard-monthly EVERGREEN 订阅生成发票，手动通过测试插件支付 |
| 恢复 | 停服后用同一数据库和插件目录再次启动，验证插件 RUNNING、订阅 ACTIVE、发票金额/COMMITTED/余额为零以及唯一 SUCCESS/PURCHASE 交易 |

测试使用 `postgresql` profile；`travis` profile 强制 H2，不能用于这组 PostgreSQL 验收。DB 参数经 `-DsurefireArgLine` 传入，并保留英文 locale、四个 add-opens。现有测试 DB helper 固定 localhost:5432，脚本拒绝用其他地址运行这组 slow 测试。不同模块顺序执行。

HTTP 冒烟使用生产模式（`server.test.mode=false`），没有替换 Guice 模块或模拟 HTTP 响应。支付通过已有本地成功型 hello-world 插件，不调用外部支付网关；插件交易内存的持久化不在本轮保证范围。发票列表需 `includeInvoiceComponents=true` 才能按实际金额/余额判断应付账单。

## 输出与清理

默认输出在 `var/release-validation/<时间>-<随机后缀>/`，不进入 Git。`--output` 可以指定新的目录；完整构建时不能放在仓库的 Maven target 目录中，以免 clean 删除报告。

- `report.json`：阶段、命令、退出码、构建/ABI 数量、实际测试数量、库名、DDL/产物摘要和日志路径；不含数据库密码。
- `build.log`、`postgres-*.log`、`server-1.log`、`server-2.log`：原始结果。输出目录权限 0700；临时配置位于其中，不提交或作为 CI 制品上传。
- 正常成功：停止两次启动的本次 Java 进程，并删除本次创建的数据库；原有数据库、容器和 Redis 数据不清理。
- 失败、超时或中断：非零退出，停止本次服务；保留库名和日志便于诊断。Maven/Java 子进程按进程组终止，超时后强杀；服务被强杀会将验收标为失败。
- `--keep-databases`：成功也保留本次库。确认诊断完成后，可对报告列出的精确库名手动 `dropdb`；不要批量按前缀删除其他运行中的验收库。

## 守卫与 CI

```bash
python3 -m unittest discover -s tools/validation -p 'test_*.py' -v
```

守卫覆盖越权清库、DDL 错误保留、子命令失败/超时、空测试/跳过/错误后端、缺失插件及错误交易/账单状态。新增守卫已逐项临时破坏保护条件确认检出，详见 [验证报告](../../changes/archive/lpr-release-validation/validation.md)。

GitHub workflow 在现有全量构建及运行时回归之后，复用 job 的 PostgreSQL 16 / Redis 7 services 执行同一入口（`--skip-build`），上传 JSON 报告与日志。配置了 Linux 验收入口，不等于本机已执行过远端 CI。

本轮不覆盖 MySQL、真实多机/NFS、进程外插件生产化、Redis 缓存兼容性或全项目 slow/DB 测试。
