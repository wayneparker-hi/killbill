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

`PluginInstaller.install` SHALL 拒绝安装已存在的版本目录，防止在 live ClassLoader 下替换 jar。

#### Scenario: 重复安装同一版本
- **IF** 调用 install 且目标版本目录已存在
- **THEN** 抛 `IllegalStateException`

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

`uninstall` SHALL 只删文件，不停止插件；调用方必须先通过生命周期停止。

#### Scenario: 卸载运行中的版本
- **IF** 未停止就调用 uninstall
- **THEN** 文件被删除（或报错），但运行时不会自动停止服务；文档声明这是危险操作

## MODIFIED Requirements

无。

## 实现与验证对照

| Requirement | 实现（文件:行 / 组件） | 验证（测试类） |
|---|---|---|
| 目录布局 | `PluginRepository` | `TestPluginInstaller.testInstallLaysOutTheVersionWhereTheRepositoryLooksForIt` |
| 身份校验 | `PluginRepository.load` | `TestPluginRuntimeEndToEnd.testAPluginWhoseDescriptorContradictsItsLocationIsRejected` |
| 不覆盖运行版本 | `PluginInstaller.install` exists 检查 | `TestPluginInstaller` |
| SHA-1 校验 | `PluginInstaller.verify` | `TestPluginInstaller` |
| 提取 descriptor | `PluginInstaller.descriptorInside` | `TestPluginInstaller` |
| 卸载语义 | `PluginInstaller.uninstall` | `TestPluginInstaller` |
