# Capability: Plugin Installation & Repository

> Kill Bill 的插件部署能力：把插件 artifact 安全地放到磁盘上，并按固定目录布局让运行时发现；拒绝覆盖正在运行的版本。当前由 LPR 实现。

## Requirements

### Requirement: 固定目录布局

插件目录 SHALL 为 `plugins/<plugin-id>/<version>/plugin.jar` + `plugin.yaml`，可选 `lib/`。

#### Scenario: 扫描插件目录
- **WHEN** 运行时扫描 `plugins/`
- **THEN** 按目录名与 descriptor 校验身份，加载指定版本

### Requirement: Descriptor 与目录名一致

`plugin.yaml` 中的 `id` 与 `version` SHALL 与所在目录名一致，否则拒绝加载。

#### Scenario: 目录名与 descriptor 不符
- **IF** 目录 `stripe/1.0.0` 的 descriptor 声明 `id: adyen`
- **THEN** 抛异常，不加载

### Requirement: 不覆盖正在运行的版本

`PluginInstaller.install` SHALL 禁止覆盖版本目录。共享模式下，对 artifact 和 descriptor 完全相同的重复广播允许幂等成功；内容不同时拒绝。

#### Scenario: 重复安装同一版本
- **IF** 调用 install 且目标版本目录已存在
- **THEN** 本地模式抛 `IllegalStateException`；共享模式仅在字节与 descriptor 完全相同的情况下幂等成功

### Requirement: 完整性校验

安装 SHALL 支持 SHA-1 校验；校验失败时 MUST 清理临时文件并拒绝安装。

#### Scenario: 校验失败
- **IF** 提供的 sha1 与实际不符
- **THEN** 安装失败，不留半成品

### Requirement: 支持从 jar 内提取 descriptor

安装时若未提供 descriptor，SHALL 从 jar 内读取 `plugin.yaml` 并写出到版本目录。

#### Scenario: 安装不带外部 descriptor 的 jar
- **WHEN** 只提供 jar 和 sha1
- **THEN** 安装器从 jar 内拷贝 descriptor 到 `plugins/<id>/<version>/plugin.yaml`

### Requirement: 卸载不停止运行插件

`uninstall` SHALL 只删文件，不停止插件；调用方必须先通过生命周期停止。共享模式拒绝删除仍启用的目标版本；源删除不影响其他节点已缓存的版本。

#### Scenario: 卸载运行中的版本
- **IF** 未停止就调用 uninstall
- **THEN** 文件被删除（或报错），但运行时不会自动停止服务；文档声明这是危险操作

### Requirement: 可替换仓库与共享目标状态

运行时 SHALL 只依赖 `lpr-spi` 的 PluginRepository。提供本地 FS 与共享 FS 两种独立策略；目标版本、启停状态及 previous 与 revision 持久化，写入以跨进程 CAS 防止丢失更新。损坏状态 MUST 显式失败，不能退回默认版本。共享删除保留 revision。

#### Scenario: 两个节点同时提交同一 revision
- **WHEN** 两个独立进程尝试把 revision N 改为不同版本
- **THEN** 恰好一个提交成功，另一个报告冲突；读取方只看到完整 marker

### Requirement: 共享 artifact 与节点缓存隔离

共享发布 SHALL 原子且不覆盖既有版本。节点加载本地内容缓存，源端删除/替换不关闭其他节点已加载的 jar；同一版本不同 jar/descriptor 的重复安装必须拒绝，包括先卸载后重新安装；共享版本内容摘要不可复用为不同内容。

#### Scenario: 源仓库删除旧版本
- **WHEN** 节点 A 删除共享源中的旧版，节点 B 仍运行旧版
- **THEN** B 可继续延迟加载该版本的类，随后按协调顺序停止它

### Requirement: 集群目标状态协调

共享节点 SHALL 周期应用持久化目标，遗漏广播后可收敛。应用前后校验 revision；候选启动期间收到更新指令时，保留原路由并撤销过期候选。自动回滚不得覆盖新 revision。

#### Scenario: 节点错过升级和停止广播
- **WHEN** 另一节点提交版本/启停目标
- **THEN** 周期协调应用目标，进程重启后仍遵循持久化启停状态

### Requirement: 集群状态查询

集群 API SHALL 同时返回目标版本、revision、enabled 与各节点已报告的实际版本/状态/更新时间，标记缺失、过期和未收敛；不得把缺报节点判断为已收敛。

#### Scenario: 节点报告旧版本或过期
- **WHEN** 查询 pluginsInfo/cluster
- **THEN** 节点显示落后/过期，不报告全局一致

## 实现与验证对照

| Requirement | 实现（文件:行 / 组件） | 验证（测试类） |
|---|---|---|
| 目录布局 | `FileSystemPluginRepository` | `TestPluginInstaller.testInstallLaysOutTheVersionWhereTheRepositoryLooksForIt` |
| 身份校验 | `FileSystemPluginRepository.load` | `TestPluginRuntimeEndToEnd.testAPluginWhoseDescriptorContradictsItsLocationIsRejected` |
| 不覆盖运行版本 | `RepositoryFiles.publishUnlocked` | `TestPluginInstaller` |
| SHA-1 校验 | `PluginInstaller.verify` | `TestPluginInstaller` |
| 提取 descriptor | `PluginInstaller.descriptorInside` | `TestPluginInstaller` |
| 卸载语义 | `PluginInstaller.uninstall` | `TestPluginInstaller` |
| 仓库 port / 架构边界 | `PluginRepository`（lpr-spi）、独立 filesystem adapter | `TestArchitecturalConstraints.testRuntimeShipsNoInfrastructureImplementation` |
| CAS / marker 完整性 / 锁超时 | `SelectionStore`、`RepositoryFiles` | `TestFileSystemPluginRepository`（两个独立 JVM 竞争、损坏状态、版本删装防 ABA） |
| 共享缓存 | `SharedFileSystemPluginRepository`、`RepositoryCache` | `TestFileSystemPluginRepository.testCacheContainsPrivateLibrariesAndSurvivesSourceDeletion`、`TestSharedRepositoryEndToEnd.testSourceUninstallDoesNotBreakOtherNodesLazyClassLoading` |
| 协调 / 过期候选 / 回滚防旧写 | `DefaultPluginManager`、`PluginClusterCoordinator`、`PluginUpgradeMonitor` | `TestSharedRepositoryEndToEnd`、`TestPluginClusterStatusService` |
| 集群查询 / 过期与缺报 | `PluginClusterStatusService`、`PluginsInfoApi`、`PluginInfoResource` | `TestPluginClusterStatusService`、`TestPluginClusterResource`、`TestNodeInfoMapper` |
