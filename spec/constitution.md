# Kill Bill + LPR Constitution

> 不可谈判的工程铁律。违反即视为 CRITICAL。每条尽量绑机器门（CI/脚本）。
>
> 权威工作规范见仓库根目录 [AGENTS.md](../AGENTS.md)；本文件用 SDD 的 Article 形式收敛其不可让步部分，并绑定验证方式。

## Article I — `lpr-core` 只依赖 Port，不依赖具体基础设施

`lpr-core` 的 classpath 上 SHALL NOT 出现任何 adapter 实现类（ClassLoader、描述符解析器、EventBus 实现），SHALL NOT 出现 SOFAArk / Guava / Micrometer / SnakeYAML / Jackson / OSGi / Felix / Guice。

LPR API（`lpr-api`）的方法签名 SHALL NOT 暴露第三方类型。

**CI 门**：`TestArchitecturalConstraints` 通过。

## Article II — 插件启动早于 Core Service，且遵守事件顺序

`START_PLUGIN` 属于 `STARTUP_PRE` 序列，SHALL 在所有 Core service 的 `START_SERVICE` 之前完成。

插件 `Plugin.start()` SHALL NOT 调用 Core API 执行业务逻辑；此类工作 MUST 等待 `.../lifecycle/STARTED` 事件。

**CI 门**：`TestPluginRuntimeEndToEnd` 与相关生命周期测试通过；变更需 review 是否改动 `LifecycleLevel` 顺序。

## Article III — 公共 API 由 Parent ClassLoader 加载，插件实现包除外

`org.killbill.*` / `javax.*` / `jakarta.*` / `org.joda.time` / `org.slf4j` / `org.apache.shiro` SHALL 走 parent-first。

`org.killbill.billing.plugin.*` SHALL 走 child-first，因此平台侧插件 API MUST NOT 放在该包下。

跨插件边界传递的第三方类型 MUST 声明为 `provided` 作用域。

**CI 门**：`TestClassLoaderIsolation` 通过；依赖分析（dependency-scope / duplicate-finder）无异常；新插件 pom 必须 review `provided` 作用域。

## Article IV — 资源必须释放引用，不能只关闭

插件创建的线程、连接、订阅、watcher、executor SHALL 注册到 `ResourceRegistry`。

停止插件时，运行时 MUST 先撤销服务、再调 `Plugin.stop()`、再释放资源、再关闭 ClassLoader、最后丢弃所有引用，否则 ClassLoader 无法被 GC。

**CI 门**：`TestClassLoaderUnloading` 通过；新增 `AutoCloseable` 资源必须 review 是否进 `ResourceRegistry`。

## Article V — 服务注册按 `(pluginId, version)` 作用域

服务注册与撤销 MUST 以 `(pluginId, version)` 为作用域，不能只按 `pluginId`。无空窗升级期间，新旧版本共享同一个 `pluginId` 时，撤销旧版本 SHALL NOT 影响新版本。

**CI 门**：`TestPluginRuntimeEndToEnd.testUpgradingLeavesNoWindowWithoutAProvider` 与 `testRollbackReturnsToThePreviousVersion` 通过。

## Article VI — Core 不得依赖任何具体插件

Kill Bill Core / Platform 代码 SHALL NOT 出现 `instanceof XxxPlugin` 或指向具体插件的 pom 依赖。业务层只表达 capability + selector，由 Router/Registry 决定实现。

**CI 门**：全仓 grep 检查；依赖分析无 core→plugin 的禁止边。

## Article VII — 每条 Requirement 都有可执行测试

`spec/` 中 capability 的每条 Scenario SHALL 映射到至少一个可执行测试。新增 capability 必须先写 spec，再实现与测试。

**CI 门**：`spec/` 变更需对应测试变更；PR 必须更新「实现与验证对照」表。

## Article VIII — Spec 与代码同步

需求级行为变更 MUST 先改 `spec/`，再改代码。反模式：改代码后补文档、或文档与实现长期不一致。

**CI 门**：文档链接检查脚本（相对链接不断）；review 时检查 spec delta 与代码 diff 的对应关系。
