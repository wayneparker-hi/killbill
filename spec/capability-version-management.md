# Capability: Plugin Version Management & Hot Update

> Kill Bill 的插件运维能力：支持多版本并存、无空窗激活新版本、快速回滚；安装、激活、启动三态分离。当前由 LPR 实现。

## Requirements

### Requirement: 版本用 SemVer 比较

版本目录排序 SHALL 使用语义化版本，而不是字符串比较，避免 `10.0 < 9.0`。

#### Scenario: 版本达到两位数
- **WHEN** 存在 `9.0.0` 与 `10.0.0`
- **THEN** `10.0.0` 被视为更高版本

### Requirement: ACTIVE 是文件而非 Symlink

`ACTIVE` marker SHALL 是普通文件（`active=1.3.0`），以兼容不同平台、容器镜像层和归档工具。

#### Scenario: 容器镜像打包
- **WHEN** 插件目录被打包成容器镜像
- **THEN** `ACTIVE` marker 不失效、不暴露部分写入

### Requirement: 无空窗升级

激活新版本 SHALL 先启动新版本，再停止旧版本，中间按 `pluginId` 的查询始终返回可用实现。

#### Scenario: 升级支付插件
- **WHEN** 从 1.2.0 激活到 1.3.0
- **THEN** 1.3.0 启动并注册服务后，1.2.0 才被停止

### Requirement: 升级失败保留旧版本

若新版本启动失败，运行时 MUST NOT 停止旧版本；旧版本继续服务。

#### Scenario: 新版本 start 失败
- **IF** 1.3.0 启动抛异常
- **THEN** 1.2.0 保持 ACTIVE，查询仍返回 1.2.0

### Requirement: 支持回滚

`ACTIVE` marker SHALL 记录 `previous` 版本；rollback 按无空窗流程切回上一版本。

#### Scenario: 回滚插件
- **WHEN** 调用 rollback
- **THEN** 系统切回 previous 版本，且过程中无服务空窗

### Requirement: 激活后健康检查失败自动回滚

新版本激活后，运行时 SHALL 在可配置时间窗口内持续观察其健康状态；若健康检查失败，则自动 rollback 到 previous 版本，并记录审计事件。

#### Scenario: 新版本上线后不健康
- **WHEN** 从 1.2.0 激活到 1.3.0，且 1.3.0 的健康检查失败
- **THEN** 系统在观察窗口内自动回滚到 1.2.0，升级与回滚审计事件均被记录

### Requirement: 安装是原子操作

安装 SHALL 先把文件落到临时目录，校验通过后再原子移动到目标位置；失败时不留半成品。

#### Scenario: 安装中断
- **IF** 下载或校验失败
- **THEN** 临时目录被清理，版本目录不存在

### Requirement: 回滚观察窗口保留健康旧版

候选启动期间不得替换业务路由；启用自动回滚时，旧版本 SHALL 保持运行直到完整观察窗口结束。窗口内任一次健康失败 MUST 切回已运行旧版；过期观察任务不得回滚更新的版本。

#### Scenario: 候选先健康后失败
- **WHEN** 首次检查健康，但在观察窗口结束前变坏
- **THEN** 切回旧版，全过程查询有健康 provider

## MODIFIED Requirements

无。

## 实现与验证对照

| Requirement | 实现（文件:行 / 组件） | 验证（测试类） |
|---|---|---|
| SemVer 排序 | `SemanticVersion` / `PluginRepository.versionDirectories` | `TestSemanticVersion` |
| ACTIVE 文件 | `PluginRepository.setActiveVersion` | `TestPluginRuntimeEndToEnd.testTheActiveMarkerPinsAVersion`、`testUpdatingTheActiveMarkerLeavesNoPartialMarkerBehind` |
| 无空窗升级 | `DefaultPluginManager.activateVersion` | `TestPluginRuntimeEndToEnd.testUpgradingLeavesNoWindowWithoutAProvider` |
| 失败保留旧版 | `DefaultPluginManager.activateVersion` 异常分支 | `TestPluginRuntimeEndToEnd` |
| 回滚 | `DefaultPluginManager.rollback` / `PluginRepository.previousVersion` | `TestPluginRuntimeEndToEnd.testRollbackReturnsToThePreviousVersion` |
| 激活后自动回滚 | `PluginUpgradeMonitor` / `DefaultPluginManager.addActivationListener` / `PluginRepository.setActiveVersion(..., previousVersion)` | `TestPluginUpgradeAutoRollbackEndToEnd` |
| 原子安装 | `PluginInstaller.install` staging + atomic move | `TestPluginInstaller` |
| 完整观察窗口、旧版保留、过期回调隔离 | `PluginUpgradeMonitor` / `DefaultPluginManager.completeUpgrade/rollbackIfCurrent` | `TestPluginUpgradeAutoRollbackEndToEnd.testLaterFailureRollsBackWithoutProviderGap`、`testHealthyWindowRetiresOldRuntimeAndStaleRollbackIsRejected` |
