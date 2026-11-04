# T11 故障注入与生命周期隔离验收

范围：在 `961127a` 的 T1–T5 基线上实现 T11。未推进 T6–T10；AGENTS 保持通用约束，不记录进度。

## 实现与故障覆盖

| 场景 | 行为 | 验证 |
|---|---|---|
| start 在注册服务与资源后抛 OOM | 撤销服务、继续释放含失败回调的资源、关闭 loader、FAILED；其他插件继续启动 | `TestLifecycleFaultInjection.startupOutOfMemoryErrorDoesNotStrandServicesResourcesOrOtherPlugins` |
| stop 抛 Error | 清理继续，最终 STOPPED | `stopErrorStillReleasesResourcesAndLoader` |
| stop 忽略中断并阻塞 | 到期返回，其他插件可停止；该版本 STOPPING；释放门闩后可加载此前未加载的类并完成清理 | `timedOutStopKeepsLoaderUntilExitAndCannotRepublishOrReload` |
| 旧 Context 晚到注册 | 关闭服务 scope 后拒绝注册，不污染后续版本 | 同上 |
| 资源清理阻塞 | 同样受 stopTimeout 约束；回调结束前不关闭 loader | `blockedResourceCleanupAlsoRespectsCallerDeadline` |
| 升级时旧版停止超时 | 保留退休版本，禁止同版本重载，真实退出后允许重载 | `retiredVersionCannotReloadUntilTimedOutStopActuallyExits` |
| 停止执行器饱和 | 无排队、拒绝新操作，保留状态/服务及旧实例以便重试 | `TestDefaultPluginStopExecutor`、`TestDefaultPluginLifecycleManager.testStopCapacityRejectionPreservesActiveStateAndServices`、`rejectedRetirementStillRetainsOldInstanceForRetry` |
| 卸载发生在 stop 超时后 | 保留 jar，待实际 STOPPED 后才允许删除；判断/删除与版本操作使用同一 manager 锁 | `TestPluginNodeCommandListener.testUninstallKeepsJarWhileTimedOutStopIsStillExecuting` |
| stop worker 仍存活 | 已停止插件的 loader 可被 GC，worker 不持有完成的 action | `stoppedLoaderIsCollectableWhileStopPoolRemainsAlive` |
| 同节点并发安装/发现/激活/回滚 | 安装走真实 staging + atomic move；路由无空窗、同版本不重复加载、最终 marker 与运行版本一致，stopAll 后无残留 | `TestConcurrentPluginOperations` |

SPI：`PluginStopExecutor`。独立 adapter：`lpr-lifecycle-default`；Core 不依赖 adapter。平台新增 singleton provider，既有绑定保留；宿主停止时在 `manager.stopAll()` 后关闭执行器。TestKit 和手工装配的测试均显式传入 executor；旧 lifecycle 构造签名已全仓迁移。

配置前缀 `org.killbill.billing.plugin.lifecycle.`：`stopTimeout=30000` ms，`stopMaxThreads=8`。执行器到期仅请求中断；不合作回调占用 worker 直到实际退出。停止时保持资源 → ClassLoader → 引用清除的顺序。

## 负例守卫

临时逐项破坏条件，确认对应断言失败，再还原源码。10 项均检出：

1. 启动仅捕获 Exception，漏掉插件 Error。
2. 资源清理仅捕获 RuntimeException。
3. stop 超时立即清理并关闭仍使用中的 loader。
4. 旧 Context 继续允许注册服务。
5. 停止被拒绝后不保留仍 ACTIVE 的旧实例。
6. 发现覆盖已有运行实例。
7. 停止 worker 无上限。
8. 清理不清空 runtime 引用。
9. 把真实 lifecycle adapter 放到 Core 的 classpath。
10. 跳过卸载前的 loaded-version 判断。

日志：`/tmp/killbill-t11-negative-summary.log` 与 `/tmp/killbill-t11-negative-*.log`。架构负例使用临时 target/test-classes 文件，结束后已删除。最终 clean 构建与正例复跑负责排除残留产物。

## 构建与测试

最终结果：全量 `BUILD SUCCESS`，93 个 reactor 项目全部成功，22 个插件 ABI 门禁通过；耗时 1 分 9 秒。该构建跳过测试与下列静态检查。

clean install 后独立复跑 **198 项测试，0 失败、0 错误、0 跳过**：

| 范围 | 数量 |
|---|---:|
| Core（含架构约束） | 42 |
| Lifecycle adapter | 3 |
| ClassLoader 隔离与卸载 | 8 |
| TestKit（含 8 项新增故障/并发用例） | 25 |
| ABI | 10 |
| 平台 plugin-runtime（含卸载保护） | 66 |
| hello-world 迁移 | 4 |
| SPI / YAML / EventBus / Installer | 30 |
| 邮件 / 自定义工厂 / 支付重试 / 目录插件 | 10 |
| **合计** | **198** |

- JDK 21、Maven 3.6.3，命令与 AGENTS 一致：`mvn -B -ff -T 1C clean install -DskipTests=true`，跳过约定的 SpotBugs、dependency、scope、versions、duplicate-finder、enforcer、RAT；ABI 和 POM 排序启用。
- 全量构建日志：`/tmp/killbill-t11-full-build.log`。
- 构建后按根 CI 的两组命令独立复跑测试，第一组增加 `lpr/lpr-lifecycle-default`；测试 JVM 参数保留四个 add-opens、英文 locale。
- 最终测试日志：`/tmp/killbill-t11-final-regressions.log`。
- 早期 LPR 测试：`/tmp/killbill-t11-lpr-tests3.log`；平台运行时：`/tmp/killbill-t11-runtime-tests.log`。
- CI YAML 已解析，所有 shell 步骤通过 bash -n；远端 GitHub Actions 未执行。

开发阶段失败：首次测试因新 fixture 字符串引号错误未编译；第二次测试因误将 ACTIVE 当纯文本断言失败，实际为 Properties 文件。分别修正字符串与 marker 解析后复跑通过。日志：`/tmp/killbill-t11-first-tests.log`、`/tmp/killbill-t11-tests2.log`。

## 边界

- OOM 使用显式抛出的 `OutOfMemoryError` 注入，验证插件边界和清理路径；未通过真实耗尽 JVM 堆验证进程存活。全局资源耗尽、永不返回的代码及插件自建线程需要进程隔离，仍归 T10。
- 执行器饱和时停止可能被拒绝，需排除阻塞后重试。升级路由已切换后若旧版停止被拒绝，新版继续服务，旧实例仍受管理。
- 本次未运行 DB / 外部支付网关集成测试；既有内嵌 MySQL 与 Qualpay 仓库认证条件见 `validation.md`。
