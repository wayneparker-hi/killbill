# T1–T5 审查修复验收

日期：2026-11-04。范围：修复 R1–R8、计数漂移及不稳定测试，完成 T5；T6–T11 保留待实现。原有未提交改动保留，未提交或推送 Git。

## 行为与覆盖

| 问题 | 修复 | 自动化覆盖 |
|---|---|---|
| 业务异常被包装 | 受检异常与 Error 原样透传，仅保护失败使用平台异常 | `TestInvocationIsolation.checkedPaymentExceptionPreservesIdentity` |
| 晚到成功关闭熔断 | CAS 快照与调用代次；单个半开探测 | `TestInvocationIsolation.staleResultsCannotResetBreaker` |
| 无限 worker / 不合作任务 | 全局有界 worker、单插件额度；实际退出后释放 | `TestInvocationIsolation.ignoredInterruptionRetainsSlotAndPoolIsBounded` |
| 健康结果未传播宿主 | 健康/版本可见性事件刷新普通与单值注册表；直接替换路由 | `TestKillbillPluginServiceBridge` |
| accessor 终止周期任务 | accessor 与 probe 均在线程、TCCL、异常、超时边界内；启动仅注册延迟探针 | `TestPluginHealthCheckManager`、`TestDefaultPluginLifecycleManager` |
| 超时探针反复占 worker | 每版本最多一个未退出探针，全局有界 | `TestPluginHealthCheckManager.testUncooperativeProbeCannotOccupyAllWorkers` |
| 升级回滚空窗 / 过早结束观察 | 启动候选时保留旧路由；全窗口保留旧实例；延迟失败切回；旧 watch 校验版本 | `TestPluginUpgradeAutoRollbackEndToEnd` |
| 失败启动 ClassLoader 指标漂移 | 失败清理后计数归还 | `TestDefaultPluginLifecycleManager.testFailedStartReleasesClassLoaderMetric` |
| ABI 泛型/继承漏检与缺输入放行 | 解析 Signature、服务边界类型变量、继承方法与异常；缺输入失败 | `TestAbiValidator` |
| 旧邮件插件 shaded 依赖冲突 | 使用当前 sibling 私有实现；工厂接口迁入 parent-loaded API；每次渲染选择服务 | `TestSharedFormatterContract`、`TestDynamicInvoiceFormatter` |

## 已运行测试

使用 JDK 21、真实 Maven 3.6.3，按 AGENTS 中约定跳过静态检查，测试参数：

```bash
-DsurefireArgLine='-Xmx1024m -Duser.language=en -Duser.country=US --add-opens java.base/java.lang=ALL-UNNAMED --add-opens java.base/java.io=ALL-UNNAMED --add-opens java.base/java.util=ALL-UNNAMED --add-opens java.base/java.base=ALL-UNNAMED'
```

| 范围 | 测试数 | 结果 |
|---|---:|---|
| LPR core（含架构约束） | 41 | 通过 |
| ClassLoader 隔离与卸载 | 8 | 通过 |
| TestKit 端到端 | 17 | 通过 |
| ABI validator | 10 | 通过 |
| 平台 plugin-runtime | 65 | 通过 |
| hello-world 迁移 | 4 | 通过 |
| SPI / YAML / EventBus / Installer | 30 | 通过 |
| 邮件动态工厂 / 跨 ClassLoader 工厂 / 支付重试 | 8 | 通过 |
| 目录测试插件 | 2 | 通过 |
| **合计** | **185** | **0 失败、0 跳过** |

主命令：`mvn -pl lpr/lpr-core,lpr/lpr-classloader-default,lpr/lpr-testkit,lpr/lpr-abi-validator,killbill-platform/plugin-runtime,plugins/hello-world-plugin test`。
补充命令选择 SPI、YAML、EventBus、Management 与四个插件模块，并通过 `-Dtest` 选择上表相关测试；未把 DB/网关集成测试算作通过。

最终 clean install 后，按根 CI 的两组命令重新执行全部 185 项测试，均通过且无跳过。最终日志：`/tmp/killbill-fix-final-regressions.log`。早期分组日志：`/tmp/killbill-fix-verified-tests.log`、`/tmp/killbill-fix-additional-tests.log`、`/tmp/killbill-fix-abi-final-tests.log`、`/tmp/killbill-fix-catalog-tests.log`。
优惠券兼容性探针另外验证 quantity、BCD、external key 与 DateTime 原值保留：`/tmp/killbill-coupon-probe.log`。

## 负例守卫

临时逐项破坏生产条件，运行对应测试，确认断言失败，再精确还原源码。15 项全部检出：异常包装、旧代熔断结果、超时提前释放额度、宿主忽略健康、accessor 越过隔离边界、过早结束观察、忽略泛型、缺输入放行、漏减 ClassLoader 计数、启动执行 accessor、探针无单版本额度、单值注册表按不同名字分裂路由、工厂选择被缓存、继承自非默认前缀契约的方法被跳过、重复类规则被关闭。

日志：`/tmp/killbill-negative-summary.log`、`/tmp/killbill-negative-extra-summary.log`、`/tmp/killbill-negative-abi-extra-summary.log`。负例失败属于预期结果；还原后须以最终正例复跑为准。

根目录 `.github/workflows/ci.yml` 已配置同样的全量构建、ABI 门禁和 185 项测试；官方 Qualpay 源码使用固定 commit。GitHub Actions 尚未实际执行，本地命令验收与远端 CI 执行结果分开报告。

## 构建与环境

全 reactor 使用 AGENTS 约定命令：JDK 21，`mvn -B -ff -T 1C clean install -DskipTests=true`，跳过 SpotBugs、dependency、scope、versions、duplicate-finder、enforcer、RAT。ABI 门禁保持启用。未跳过 POM 排序；classpath 在 prepare-package 生成，校验在 verify 执行，避免排序改变同阶段插件执行顺序。

最终结果：`BUILD SUCCESS`，92 个 reactor 项目全部成功、无失败或跳过项目；22 个插件 ABI 校验通过。耗时 4 分钟，日志：`/tmp/killbill-fix-full-verified.log`。该构建跳过测试，测试结果以上述独立复跑为准。

修复过程中遇到的失败：

| 命令/阶段 | 输出与影响 | 处理 |
|---|---|---|
| 基线全量 clean install | 自定义邮件插件 ABI 11,135 条冲突 | 修复依赖与共享契约 |
| 修复初期编译/测试编译 | 邮件 listener 字段类型、Payment API 测试依赖缺失 | 调整类型与 test scope 依赖后通过 |
| 首次扩大全量构建 | 内部 DAO 泛型误报；hello-world/Adyen 共享类型 scope 错误 | 泛型沿服务方法实际使用的变量解析；修 provided |
| 目录测试插件 verify | Joda scope 错误、仅宿主依赖的重复类被误判 | 修 provided 与跨边界重复规则，补回归 |
| 后续全量构建 | 优惠券、支付重试、Qualpay 使用旧 SDK API；支付重试测试文件名与类名不符、缺 Mockito | 适配当前 SDK、修测试文件与依赖 |
| Qualpay 依赖下载 | GitHub Maven 返回 401，阻塞剩余 reactor | 从官方 tag 本机构建同版本依赖，未更改项目版本 |
| 邮件整个模块 mvn test | 内嵌 MySQL 缺 `mysql-Mac_OS_X-aarch64.tar.gz`，DB suite 初始化失败并跳过后续项 | 单独运行本变更相关无 DB 测试通过；DB suite 仍未验收 |

Qualpay 本机依赖来源：`https://github.com/killbill/qualpay-java-client` 的 `qualpay-java-client-1.1.9` tag，commit `f705d5aba34de727f9524002428e7ea5aaf2d4bb`。源码位于 `/tmp/killbill-qualpay-client-1.1.9`，仅以 `install` 写本机 Maven 缓存；JDK 21 构建时覆盖编译目标为 Java 8，并跳过测试与旧版 javadoc。日志：`/tmp/killbill-qualpay-client-build.log`。其他机器仍需 GitHub 包访问权限或同样的官方源码构建。

## 验收边界与后续

- 全仓业务 DB、外部支付网关及线上指标端点未在本次验收；Qualpay 改动仅为继承字段名称适配。
- JVM 内无法强制终止任意不合作插件，worker/额度只能限制增长；强隔离归 T10。
- 审计有 runtime actor、版本、原因和时间，输出日志及内存事件；尚无持久审计查询。
- Metaspace 指标、T6 多节点一致性、T7 API 版本协商、T8 事件背压与持久化、T9 安装安全、T10 进程外插件、T11 系统混沌测试仍待实现。
