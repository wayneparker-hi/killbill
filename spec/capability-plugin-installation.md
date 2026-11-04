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

安装 SHALL 计算 SHA-256；给定的 `sha256` 必须为 64 位 hex 并匹配实际 artifact。SHA-1 不再支持，旧命令参数必须拒绝。平台可要求摘要必填；校验失败时 MUST 拒绝发布。

#### Scenario: 校验失败
- **IF** 提供的 sha256 与实际不符
- **THEN** 安装失败，不留半成品

#### Scenario: 旧参数与必填策略
- **WHEN** 命令携带 sha1，或平台要求 sha256 而请求未提供
- **THEN** 明确拒绝，版本目录不出现

### Requirement: 可选完整签名与证书校验

平台 SHALL 可配置强制 JAR 签名与 PEM 信任证书；完整读取每个 payload entry 并使用 JDK 签名及 PKIX 验证。所有 payload 必须有共同可信 signer；证书有效期、签名用途及 codeSigning EKU 必须有效。不能使用命令参数降低要求。离线校验不包含撤销状态查询。

#### Scenario: 篡改或不完整签名
- **WHEN** 已签名内容被篡改、增加未签名 entry、JAR 未签名或 signer 不可信/失效
- **THEN** 安装失败，无已发布版本；合法可信完整签名允许安装

#### Scenario: 外部 descriptor 覆盖签名内容
- **WHEN** 强制签名且外部 descriptor 不等于 jar 内的已签名 descriptor
- **THEN** 拒绝安装，不能切换已认证入口或配置

#### Scenario: 签名包引用外部类路径
- **WHEN** 包含 manifest Class-Path 或 META-INF/INDEX.LIST
- **THEN** 拒绝安装，防止加载未验证的外部依赖

### Requirement: 有界安装读取

平台 SHALL 限制 file/HTTP 包读取大小（默认 64 MiB），签名 JAR 展开内容总量（默认 256 MiB）及内嵌 descriptor（1 MiB）。平台大小配置必须为正，包上限不得超出 int 范围。

#### Scenario: 超过读取上限
- **WHEN** 文件或 chunked HTTP 响应超过包上限，或签名包展开总量/descriptor 超限
- **THEN** 安装失败且不发布；包大小等于上限时允许继续验证

### Requirement: 来源审计

平台安装 SHALL 持久化关联同次操作的开始、验证与结果记录，包含脱敏请求及重定向实际来源、实际 SHA-256、descriptor 摘要、签名要求与 signer 指纹。凭据与异常原文不得进入审计。审计目录不随卸载删除；发布前审计失败必须阻止发布。

#### Scenario: 校验失败与安装成功
- **WHEN** 安装校验失败或发布成功
- **THEN** 可从审计记录区分阶段、结果与实际内容，原 URI 的凭据/query/fragment 不泄漏

#### Scenario: 审计目录的符号链接别名
- **WHEN** 配置路径经符号链接解析后落入插件/version 目录
- **THEN** 装配失败，不能让卸载删除审计记录

#### Scenario: 发布后的审计写入失败
- **WHEN** artifact 已发布而结果记录写失败
- **THEN** 显式报告 artifact 已发布、审计未完成，保留文件；不得报告已回滚

### Requirement: 支持从 jar 内提取 descriptor

安装时若未提供 descriptor，SHALL 从 jar 内读取 `plugin.yaml` 并写出到版本目录。

#### Scenario: 安装不带外部 descriptor 的 jar
- **WHEN** 只提供 jar 和 sha256
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
| SHA-256 / 必填策略 / 旧参数拒绝 | `ArtifactSource`、`PluginInstaller.verify`、`PluginNodeCommandListener` | `TestPluginInstaller`、`TestPluginInstallAudit`、`TestPluginNodeCommandListener` |
| 提取 descriptor | `PluginInstaller.descriptorInside` | `TestPluginInstaller` |
| 卸载语义 | `PluginInstaller.uninstall` | `TestPluginInstaller` |
| 仓库 port / 架构边界 | `PluginRepository`（lpr-spi）、独立 filesystem adapter | `TestArchitecturalConstraints.testRuntimeShipsNoInfrastructureImplementation` |
| CAS / marker 完整性 / 锁超时 | `SelectionStore`、`RepositoryFiles` | `TestFileSystemPluginRepository`（两个独立 JVM 竞争、损坏状态、版本删装防 ABA） |
| 共享缓存 | `SharedFileSystemPluginRepository`、`RepositoryCache` | `TestFileSystemPluginRepository.testCacheContainsPrivateLibrariesAndSurvivesSourceDeletion`、`TestSharedRepositoryEndToEnd.testSourceUninstallDoesNotBreakOtherNodesLazyClassLoading` |
| 协调 / 过期候选 / 回滚防旧写 | `DefaultPluginManager`、`PluginClusterCoordinator`、`PluginUpgradeMonitor` | `TestSharedRepositoryEndToEnd`、`TestPluginClusterStatusService` |
| 集群查询 / 过期与缺报 | `PluginClusterStatusService`、`PluginsInfoApi`、`PluginInfoResource` | `TestPluginClusterStatusService`、`TestPluginClusterResource`、`TestNodeInfoMapper` |
| 安装字节上限 | `ArtifactStreams`、`ArtifactVerificationPolicy` | `TestPluginInstallAudit#testFileAndHttpArtifactLimitsPreventPublication`、`TestJarSignatureVerifier#testExpandedJarAndDescriptorLimitsAreEnforced` |
| JAR 签名 / PKIX / descriptor | `JarSignatureVerifier`、`ArtifactVerificationPolicy` | `TestJarSignatureVerifier`、`TestSignedPluginInstallation` |
| 审计目录卸载保护 | `PluginRuntimeModule.validateAuditLocation` | `TestPluginRuntimeModule`：直接路径与符号链接别名 |
| 来源 / 阶段审计与失败边界 | `PluginInstallAuditSink`、`InstallAuditOperation`、`FileSystemPluginInstallAudit` | `TestPluginInstallAudit`、`TestFileSystemPluginInstallAudit` |
