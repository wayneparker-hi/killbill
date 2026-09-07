# 文档索引

本目录是这个工程的**知识库**：为什么这么做、现状是什么样、下一步做什么。
面向 AI 的**工作规范与约定**不在这里，在根目录的 [`../CLAUDE.md`](../CLAUDE.md)。

---

## 使用（usage.md）

[usage.md](usage.md) —— **从零跑起来**：构建、建库、起服务器、插件配置与目录布局、
装插件/启停/升级/回滚、排障、以及与上游的差异速查。

业务语义（订阅、发票、支付、目录）本 fork 没改，看
[官方文档](https://docs.killbill.io) 即可。**讲业务的看官方，讲插件的看这里。**

---

## 设计（design/）

| 文档 | 内容 |
|---|---|
| [lightweight-plugin-runtime.md](design/lightweight-plugin-runtime.md) | **LPR 设计**。它解决什么问题、四条设计原则、自研 vs 复用的边界与 Port & Adapter、核心抽象、生命周期与停止顺序、ClassLoader 委派模型、Service Registry、版本管理与热更新、测试体系、开发顺序 |

**这是架构决策的源头依据**，实施中遇到分歧以此为准。

文末「设计与实现的三处出入」列出了实施中**基于证据推翻或修正**的部分：
ClassLoader 默认后端改为自研、EventBus 没有抽 port、`org.killbill.billing.plugin.` 挖回 child-first。

---

## 架构图（architecture/）

[architecture/README.md](architecture/README.md) —— 两套 SVG 图，可直接打开或贴进文档：

- **业务架构** 8 张：整体分层图、价值流、能力地图、对象生命周期协作、扩展点地图、异常决策矩阵、指标树、商业化接入的三条链路
- **4+1 视图** 7 张：场景、逻辑、开发、进程（出账链路 + 热升级）、物理、视图整合与对齐

想快速理解「这个 fork 与上游的差别落在哪」，看
[extension-points.svg](architecture/business/extension-points.svg)（13 个扩展点）和
[3-development-view.svg](architecture/4plus1/3-development-view.svg)（88 模块六层与两条禁止边）。

---

## 分析（analysis/）

改造前对现有代码的摸底。**每个结论都标注了文件路径和行号**，实施时直接按图索骥。

| 文档 | 回答的问题 |
|---|---|
| [01-maven-structure.md](analysis/01-maven-structure.md) | 6 个仓库怎么组织的？依赖图长什么样？合并成单仓库有哪些坑？ |
| [02-osgi-platform-implementation.md](analysis/02-osgi-platform-implementation.md) | 现有 OSGi 实现做了什么？哪些是 Felix 强绑定、哪些是 Kill Bill 自研抽象？插件怎么被发现和加载？ |
| [03-osgi-coupling-in-core.md](analysis/03-osgi-coupling-in-core.md) | 业务核心对 OSGi 的耦合有多深？改造要碰多少地方？ |

### 三个最关键的分析结论

1. **耦合面比想象中浅**：主工程 main 源码 `import org.osgi.*` 是 **0 处**；124 处 `org.killbill.billing.osgi.*` 里 **88 处（71%）只是两个零依赖的纯接口**（`OSGIServiceRegistration` / `OSGIServiceDescriptor`）。
2. **Felix 强绑定只有 40 个文件**，且 `osgi` 模块 31 个文件里 **21 个零 OSGi 依赖、可直接搬运**。
3. **JRuby 桥接已经死了**——`PluginFinder` 直接抛 `UnsupportedOperationException("Non-Java plugins aren't supported anymore")`，只剩 README 和测试里的死路径。纯删除项。

---

## 迁移（migration/）

| 文档 | 内容 |
|---|---|
| [osgi-to-lpr-plan.md](migration/osgi-to-lpr-plan.md) | **实施主线**。7 个 Phase，每个 Phase 的改动清单与验收判据。当前进度也记录在这里 |
| [plugin-migration-guide.md](migration/plugin-migration-guide.md) | **插件作者看这个**：如何把一个 OSGi bundle 插件迁到 LPR。含对照表、五个步骤、包名对 ClassLoader 的影响、验证方式与检查清单 |

### 当前进度

| Phase | 状态 |
|---|---|
| 0 — 单仓库聚合 | ✅ 完成（79 模块，`BUILD SUCCESS`） |
| 1 — LPR 骨架（api/spi/core） | ✅ 完成 |
| 2 — ClassLoader PoC（**硬门禁**） | ✅ **通过**（隔离性 + 可卸载性，8 项测试） |
| 3 — Registry / Lifecycle / Resource / EventBus | ✅ 完成（33 项测试） |
| 4 — 插件仓库 / 描述符 / PluginManager / TestKit | ✅ 完成（含 15 项端到端测试） |
| 5 — 接入 Kill Bill 核心 | ✅ 完成（6 个业务模块迁移，**1128 项 Kill Bill 测试全绿**） |
| 6 — 平台侧实现替换 | ✅ 完成：`plugin-runtime` 已接进 `KillbillPlatformModule`，含服务桥 / TCCL 代理 / 生命周期 / HTTP 路由 / 类型化与单值注册表 |
| 7 — 删除 OSGi + 迁移存量插件 | ✅ **OSGi 已彻底删除**：3 个模块树、141 个文件、16408 行；Felix / `org.osgi` / `maven-bundle-plugin` / bnd 指令 / 包白名单全部清除<br>**扩展点全部迁移**：13 个注册表类型、平台服务发布、事件分发、节点命令、HTTP 路由<br>**插件框架 + 16 个插件已迁移**（框架 1312 项测试、插件 117 项）；`logger` bundle 是 OSGi LogService 桥接，随 OSGi 作废<br>KPM 由 `lpr-management` 替代，接在既有节点命令上 |

---

## 开发（development/）

| 文档 | 内容 |
|---|---|
| [plugin-development.md](development/plugin-development.md) | **写新插件看这个**：`plugin.yaml` 字段与校验规则、`PluginContext` 能拿到什么、pom 作用域规则、`lpr-testkit` 用法、热更新与回滚的操作约定 |
| [build-environment.md](development/build-environment.md) | 构建命令、JDK/Maven 要求，以及 7 个会浪费时间的环境坑（jenv shim 覆盖 JAVA_HOME、`mirrorOf=*`、sortpom 重写 pom、嵌入式 MySQL/Redis 无 aarch64、`-D` 传不进 surefire fork、JVM locale、PG 序列不回退） |
| [issue-log.md](development/issue-log.md) | **问题账本**：实施过程中遇到的问题、根因、解决方式，以及各自沉淀到了哪条约束/哪个守卫 |

---

## 文档维护约定

- **分析报告是快照**，反映写作时的代码状态。代码改了之后，如果结论失效，要么更新要么标注失效，不要留着误导后来者。
- **设计文档不改**（design/ 下的 v1/v2 是历史决策依据）。设计变更写进 migration/ 的方案里，并说明相对 v1/v2 改了什么、为什么。
- **进度只在一个地方记**：`migration/osgi-to-lpr-plan.md` 的 Phase 状态 + 本文的进度表。不要在多处复制进度，会不一致。
