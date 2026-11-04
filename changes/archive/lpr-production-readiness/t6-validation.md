# T6 多节点插件一致性验收

基线：`4ca91ae`（T11，93 个 reactor 项目、198 项独立回归）。T7–T10 保持待办。

## 实现与验证

| 范围 | 实现 | 可执行验证 |
|---|---|---|
| 仓库边界 | `PluginRepository`、`PluginDeployment`、`RepositorySelection` 放 lpr-spi；文件实现迁入独立 adapter | `TestArchitecturalConstraints`，Core classpath 不含旧仓库或两种文件 adapter |
| 本地与共享仓库 | `FileSystemPluginRepository`、`SharedFileSystemPluginRepository`；共享模式先持久化停止目标，再原子发布 | `TestFileSystemPluginRepository`，本地排序、旧 ACTIVE 兼容、重复广播幂等、内容冲突拒绝 |
| 跨进程 CAS | 每插件 advisory file lock、revision 校验、同目录原子替换；锁超时与损坏 marker 显式失败 | 两个独立 JVM 同时提交同一 revision，恰好一个成功；损坏/锁超时测试 |
| 删装与缓存 | 删除保留 revision 与版本 jar/descriptor 摘要；节点内容缓存包含私有库 | revision 防 ABA、卸载后不同内容拒绝、源删除后的真实延迟类加载 |
| 协调与版本切换 | 候选启动后 CAS；失败清理候选、保留原路由；周期协调不回写目标 | `TestSharedRepositoryEndToEnd`：遗漏广播后的升级/回滚/停止、停机后重启、启动期间远端 STOP 冲突、发现后目标变化 |
| 自动回滚防旧写 | 观察任务携带 applied revision；回滚/窗口结束在 manager 锁内再校验；按 watch 实例撤销任务 | `testOldRollbackAndCompletionCannotOverwriteNewRevision` 与既有自动回滚端到端测试 |
| 平台装配 | skife-config 模式/目录/期限；singleton coordinator；先关闭协调器再关闭运行时 | `TestPluginRuntimeModule.testSharedRepositoryConfigurationIsWired`、`TestPluginClusterStatusService` 的失败重试与禁用启动测试 |
| 节点上报与集群 API | 复用 node_infos；新增详细状态与 revision，修复反序列化把 updatedDate 错读为 bootTime | `TestNodeInfoMapper`：新字段往返、旧 JSON 兼容及更新时间 |
| REST | `GET /1.0/kb/pluginsInfo/cluster`，目标与已知节点报告、过期与收敛标志 | `TestPluginClusterStatusService`、`TestPluginClusterResource` 的响应/JSON 测试 |

原 `providePluginRepository` 改为按配置返回仓库 SPI；installer/manager provider 的消费关系保持。新增 coordinator 与 cluster-status singleton，既有 `bind()` / `@Provides` 无删除。旧 Core 仓库引用已全仓迁移。

## 负例守卫

逐项临时破坏后确认测试断言失败，再还原。13 项检出：

1. 绕过 selection CAS（独立 JVM 竞争）。
2. 共享首次安装错误启用。
3. 共享加载绕过本地缓存。
4. 查询忽略节点 applied revision。
5. 查询忽略报告过期。
6. 节点 updatedDate 错读 bootTime。
7. Core 内置旧仓库实现。
8. Core classpath 出现本地仓库 adapter。
9. Core classpath 出现共享仓库 adapter。
10. 候选提交时错误采用最新 revision，覆盖候选启动期间的远端停止。
11. 旧观察任务不检查自己的 revision。
12. 共享版本卸载后丢失内容身份约束。
13. 拒绝旧仓库内容冲突前先写目标状态，意外关闭已有目标。

架构三项分别独立注入临时类，并在每项结束后删除源码及编译产物，避免残留影响下一项。额外移除本地 selection 断言时，仓库 CAS 仍拒绝过期候选；该冗余保护实验通过，不计入上述负例数量。

日志：`/tmp/killbill-t6-negative-summary.log`、`/tmp/killbill-t6-negative-extra-summary.log`、`/tmp/killbill-t6-negative-*.log`。最终 clean 构建与完整正例复跑排除残留。

## 构建与测试

全量构建 **94 个 reactor 项目成功、22 个插件 ABI 门禁通过**；构建本身跳过测试。构建后按 CI 三组命令独立运行 **224 项测试，0 失败、0 错误、0 跳过**。

| 范围 | 数量 |
|---|---:|
| Core（含架构约束） | 42 |
| Lifecycle adapter | 3 |
| ClassLoader 隔离与卸载 | 8 |
| Repository adapter（含独立 JVM） | 10 |
| TestKit（含 5 项共享仓库端到端） | 30 |
| ABI validator | 10 |
| 平台 plugin-runtime | 71 |
| hello-world 迁移 | 4 |
| SPI / YAML / EventBus / Installer | 30 |
| 邮件 / 自定义工厂 / 支付重试 / 目录插件 | 10 |
| 节点 JSON / 更新时间 / 兼容 | 5 |
| 集群 REST 响应 | 1 |
| **合计** | **224** |

- JDK 21、真实 Maven 3.6.3，使用 AGENTS 的 `mvn -B -ff -T 1C clean install -DskipTests=true`；约定的 SpotBugs、dependency、scope、versions、duplicate-finder、enforcer、RAT 跳过，ABI/POM 排序启用。
- 全量构建日志：`/tmp/killbill-t6-verified-build.log`。
- 回归与测试 JVM 参数：`.github/workflows/ci.yml`；保留四个 add-opens 与英文 locale。执行脚本 `/tmp/killbill-t6-final-regressions.sh`，日志 `/tmp/killbill-t6-final-regressions.log`。
- CI 已加入 repository、节点 JSON 和集群 REST 测试；YAML 解析及所有 shell 步骤 `bash -n` 通过。未触发远端 Actions。

开发阶段失败及处理：

| 命令 / 日志 | 输出 | 处理与影响 |
|---|---|---|
| `mvn -pl lpr/lpr-repository-filesystem -am test`，`/tmp/killbill-t6-storage-tests.log` | 8 项中 2 项失败：新 fixture 未声明 `id` | 修正 fixture 的 YAML 键后通过 |
| `mvn -pl lpr/lpr-testkit,lpr/lpr-repository-filesystem -am test`，`/tmp/killbill-t6-e2e-tests.log` | 新动态 fixture 调用不存在的 `PluginContext.getService` | 改用现有 `getPlatformService`，5 项双节点用例通过 |
| 平台 `-am install -DskipTests=true`，`/tmp/killbill-t6-pre-test-install.log` | 测试编译缺 `org.mockito` | 添加 parent 已管理版本的 test 依赖 |
| 平台/LPR `-am test`，`/tmp/killbill-t6-all-subset.log` | `TestDistributedClockMock.setUp`: `Can't start redis server`，1 配置失败、2 跳过 | 扩大依赖测试遇到本机嵌入式 Redis 限制，与构建环境文档坑 5 一致；不宣称整个依赖测试集通过。改用全量安装产物运行目标模块回归 |

## 边界

- 共享目录与独立 JVM 已测；**未验证真实 NFS mount**。部署须验证跨节点锁、同目录原子 rename 及实际挂载配置。
- 状态 API 是数据库内已知节点的报告快照；缺报、过期、旧协议、STOPPING 或 revision 落后均不算收敛，不提供分布式事务保证。
- `localNodeOnly` 限定命令投递，不能把共享目标改为某节点私有；宿主 shutdown 不关闭共享目标。
- 缓存不自动回收；停止节点且确认无引用后再清理。禁止手工原地修改共享 artifact、私有库及 ACTIVE；jar/descriptor 的同版本身份在卸载后仍保留。
- 未运行 DB / 外部支付网关集成测试；未处理 T7–T10，尤其 SHA-1 安装校验升级仍属 T9。本次缓存与版本身份使用 SHA-256，不等同于完成来源认证或签名校验。
