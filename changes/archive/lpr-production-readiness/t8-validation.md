# T8 验证记录

## 范围与基线

- 实现可选 durableEvents、固定数据消息与插件作用域；保留同步内存 EventBus。
- 94 reactor / 22 ABI 基线通过：`/tmp/killbill-t8-baseline-build.log`。
- 新增测试覆盖真实文件数据库关闭后恢复、生产 DDL/DAO/dispatcher、失败重试与 FAILED、准入和信封上限、版本切换与回滚、真实插件 ClassLoader/TCCL/GC、Guice 装配。

## 负例守卫

每项仅临时改变一个保护条件，测试必须失败，随后还原源文件；涉及 install 的变异同时重建并还原本地 Maven 产物。日志位于 `/tmp/killbill-t8-mutant-<名称>.log`。

| 名称 | 结果 |
|---|---|
| envelope-budget | 检出 |
| publication-admission | 检出 |
| delivery-admission | 检出 |
| delivery-concurrency | 检出 |
| target-address | 检出 |
| subscription-address | 检出 |
| subscription-duplicate | 检出 |
| scope-expiry | 检出 |
| address-validation | 检出 |
| handler-release | 检出 |
| handler-tccl | 检出 |
| unknown-schema | 检出 |
| adapter-expiry | 检出 |
| drain-ack | 检出 |
| strict-write-errors | 检出 |
| strict-availability | 检出 |
| context-factory | 检出 |
| api-boundary | 检出 |
| limit-configuration | 检出 |
| lifecycle-assembly | 检出 |

## 开发中失败与修复

| 命令 / 日志 | 原因与修复 | 影响 |
|---|---|---|
| 目标 install；`/tmp/killbill-t8-compile.log` | 新 SPI 引用 API，补齐 lpr-spi → lpr-api POM 依赖 | 开发期编译失败 |
| 目标 install；`/tmp/killbill-t8-compile2.log` | 测试配置 stub 缺新增属性，补齐默认值 | 开发期测试编译失败 |
| 目标 storage 测试；`/tmp/killbill-t8-storage.log` | 队列 ST4 为 optional runtime 依赖，平台测试补 test scope | 初次 DB 测试未执行成功 |
| 隔离测试；`/tmp/killbill-t8-targeted.log` | 测试误用包私有 getter，改由真实插件导出探针数据 | 未扩大生产 API |
| 首轮最终回归；`/tmp/killbill-t8-final-regressions-first.log` | 2 个 worker 与 1 个 delivery permit 竞争；准入失败按设计消耗重试预算，使 handler 仅执行 2 次。仅将该重试测试 delivery permit 调为 2，背压由独立测试覆盖；对照日志 `/tmp/killbill-t8-retry-control.log` | 测试配置修正，生产实现未改 |
| 首轮 target-address 变异 | 缺跨插件对照，加入另一插件订阅后确认变异失败 | 补强测试，最终 20 项全部检出 |

## 实际边界

- DB 实测使用独立 H2 文件库和生产 queue DDL；不是 MySQL/PostgreSQL、真实多机或断电持久性验收。
- 至少一次、无顺序保证，消费须按 UUID 实现业务幂等；单节点有界并发不等于数据库 backlog 全局有界。
- 全部 externalBus 消费节点须同版本并开启能力，POLLING 目标插件须在消费节点可用；不支持混合旧节点/关闭能力节点。
- 停止 adapter 后保留平台拒绝 listener，直到 STOP_BUS；消息可进入重试/FAILED，不会因提前撤销而静默确认。
- 死信复用实际外部历史表（默认 bus_ext_events_history）的 FAILED；没有新增自动重放 API。
- 旧 post 兼容行为保留，新 postReliable/registerReliable 才承诺显式失败。
- Core 仅依赖 API/SPI；全部现有 bind/@Provides 保留，新增 2 个 provider（adapter、factory）；生命周期保留兼容构造重载。
- T10 进程外 PoC 仍 pending，本变更暂不整体归档。

## 验证命令

使用 JDK 21 和真实 Maven 路径；`/tmp/killbill-fix-mvn` 展开为 AGENTS.md 中列出的七个 `check.skip-*` 参数。所有 Maven 命令直接保留退出码，日志重定向而非管道。

```bash
/tmp/killbill-fix-mvn -T 1C clean install -DskipTests=true
bash /tmp/killbill-t8-final-regressions.sh
```

回归脚本与 `.github/workflows/ci.yml` 的三条命令相同：运行 core/lifecycle/classloader/repository/TestKit/ABI/platform/hello-world 全部 fast；SPI/YAML/event/management 及四类业务插件指定测试；最后 NodeInfoMapper/PluginClusterResource。本次新增平台测试均为 fast，因此自动进入现有 CI。

测试 fork 参数：

```text
-Xmx1024m -Duser.language=en -Duser.country=US
--add-opens java.base/java.lang=ALL-UNNAMED
--add-opens java.base/java.io=ALL-UNNAMED
--add-opens java.base/java.util=ALL-UNNAMED
--add-opens java.base/java.base=ALL-UNNAMED
```

通过 `-DsurefireArgLine` 传入；数据库测试串行运行独立临时文件库，不连接共享开发数据库。

全量构建明确跳过测试；测试结果单独记录。SpotBugs、dependency/dependency-scope/dependency-versions、duplicate-finder、enforcer、RAT 静态检查按项目构建规约跳过；不宣称全项目 slow/DB 集成测试通过。

## 审查结论

按 code-review-expert 的架构、并发、失败传播和边界检查完成自审；已修复停止阶段无订阅 ACK，以及 commons EventBus 默认串行 subscriber 绕过并发准入的边界（使用 AllowConcurrentEvents，实际 delegate 并发测试与变异验证）。发布前检查原始 payload 长度，再检查 JSON 转义后的 UTF-8 信封预算，避免为超大 payload 分配完整序列化副本。

保留所有既有装配和 public 构造入口；不开启配置时原有插件无需修改。DurableEventHandler 是公共 capability，平台不引用具体插件实现。未新增业务授权或网络入口。

## 最终结果（2026-11-04）

| 检查 | 结果 | 日志 |
|---|---|---|
| JDK 21 全量 clean install（跳过测试） | 94 reactor 项目、22 插件 ABI 门禁通过 | `/tmp/killbill-t8-final-build.log` |
| 最终三组回归 | 284 项，0 failure / error / skip | `/tmp/killbill-t8-final-regressions.log` |
| T8 新增用例 | 16 项，包含 4 项文件 DB、9 项 adapter、1 项真实 ClassLoader、2 项模块装配 | 纳入上行回归，平台共 98 项 |
| 变异守卫 | 20 项全部检出，源码及 m2 还原后回归通过 | `/tmp/killbill-t8-mutants.json` |
| 差异与格式 | `git diff --check` 通过 | 提交前检查 |

首次最终回归仅失败于上文记录的测试执行额度配置，修正后单变量对照通过，再完整执行三组回归。未改变生产实现；最终全量构建覆盖的生产代码/POM 与交付一致。
