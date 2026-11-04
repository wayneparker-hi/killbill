# T7 接口版本协商验收

基线：`e64aeac`（T9）。本次实现服务接口版本元数据、兼容性 lookup 与宿主桥接检查；T8/T10 保持待办。

## 实现与可执行验证

| 范围 | 实现 | 测试 |
|---|---|---|
| 公共契约 | `ServiceApiVersion`（lpr-api），MAJOR.MINOR.PATCH，与插件发布版本独立 | `TestServiceApiVersion`：规范数字、数值比较、兼容方向、跨 major、0.x 精确匹配 |
| 注册元数据 | `service.api.version`，旧注册默认 1.0.0；属性与解析版本不可变 | `TestServiceVersionNegotiation`：旧注册、修改输入 map 不改变版本、错误声明无存储/listener/指标副作用、类型化声明冲突 |
| 查询 | 默认按宿主要求，显式消费者版本可覆盖；不兼容返回空 | named/list lookup、显式 major 2、优先级排序、不健康与暂存过滤测试 |
| scope/生命周期 | default typed register 委托原受限注册；显式 lookup 经 scope 传到真实 registry | scope 关闭后注册拒绝、撤销与版本选择测试 |
| 宿主策略 | skife-config + 不可变 `ServiceApiRequirements` map；未配置默认 1.0.0 | `TestServiceApiRequirements`：嵌套接口 binary name、非法/重复配置；`TestPluginRuntimeModule`：查询和实际通知 typed registry 使用同一策略 |
| 桥接 | 普通、单值及健康检查均在代理创建/发布前检查；subtype 按目标消费接口 | `TestKillbillPluginServiceBridge`：不兼容不覆盖、不进入历史、不在刷新后复活、subtype 检查 |
| 真实插件边界 | 动态编译 provider/consumer，独立 ClassLoader、版本 scoped registry、生命周期 | `TestServiceVersionNegotiationEndToEnd`：接口 1.3 支持消费 1.2；插件发布版本 9→10，接口 2 不可达；回滚恢复、停止撤销 |
| 架构 | 新公共类型仅 JDK，Core 无 adapter 依赖 | `TestArchitecturalConstraints`、既有隔离/卸载及 TestKit 回归 |

无 API / bind / provider 删除；新增方法保持旧注册调用兼容。registry provider 增加策略依赖；manager provider 将相同要求交给 bridge；新增策略 provider。无 POM / 生产依赖 / 新模块改动。

## 负例守卫

逐项临时破坏实现，确认测试断言失败后还原；API 变异在测试前安装，测试后恢复并重新安装；最后全量 clean 构建清除所有变异产物。共 **18 项检出**。

1. 颠倒 provider/consumer 版本比较方向。
2. 允许跨 major 兼容。
3. 允许 0.x 非精确匹配。
4. 跳过注册元数据验证。
5. named lookup 跳过版本过滤。
6. list lookup 跳过版本过滤。
7. scoped lookup 丢弃显式版本要求。
8. 普通桥接跳过兼容检查。
9. 单值桥接跳过兼容检查。
10. healthcheck 桥接跳过兼容检查。
11. subtype 错用注册类型的要求，绕过宿主消费接口要求。
12. manager provider 未把要求传到 bridge。
13. 接受重复接口配置。
14. 新公共 API 暴露非约定类型（注入 javax.crypto.SecretKey 方法）；架构测试检出。
15. 接受前导零版本。
16. 允许类型化声明覆盖冲突版本属性。
17. 暴露可修改注册属性。
18. registry provider 丢弃宿主版本要求。

日志：`/tmp/killbill-t7-negative-summary.log`、`/tmp/killbill-t7-extra-negative-summary.log`、对应 `/tmp/killbill-t7-negative-*.log`。

## 构建与测试

- 变更前全量构建通过：94 个 reactor 项目、22 个 ABI 门禁，`/tmp/killbill-t7-baseline-build.log`（10 分 35 秒）。
- 首轮相关模块：Core 51、ClassLoader 8、TestKit 32、平台 82，共 173 项通过；日志 `/tmp/killbill-t7-first-tests.log`。
- JDK 21 与真实 Maven 3.6.3；按 AGENTS 全量构建，约定跳过 SpotBugs/dependency/scope/versions/duplicate-finder/enforcer/RAT，启用 ABI/POM 排序。构建跳过测试，测试另跑。
- 现有 CI 按模块覆盖 Core / TestKit / platform 全部 fast 测试，自动包含新增类；YAML 解析与全部 shell 步骤 `bash -n` 通过，未触发远端 Actions。
- 最终全量 clean 构建通过：**94 个 reactor 项目、22 个 ABI 门禁**，耗时 3 分 38 秒，日志 `/tmp/killbill-t7-final-build.log`。
- 最终目标回归：**268 项，0 失败、0 错误、0 跳过**；日志 `/tmp/killbill-t7-final-regressions.log`。回归脚本 `/tmp/killbill-t7-final-regressions.sh` 与 CI 三组命令一致，保留四个 add-opens 与英文 locale。

| 最终回归范围 | 数量 |
|---|---:|
| Core（含版本协商/架构约束） | 51 |
| Lifecycle adapter | 3 |
| ClassLoader 隔离与卸载 | 8 |
| Repository / Audit adapter | 12 |
| TestKit（含真实版本协商） | 32 |
| ABI validator | 10 |
| 平台 plugin-runtime | 82 |
| hello-world 迁移 | 4 |
| SPI / YAML / EventBus / Installer | 50 |
| 邮件 / 自定义工厂 / 支付重试 / 目录插件 | 10 |
| 节点 JSON | 5 |
| 集群 REST | 1 |
| **合计** | **268** |

工具说明：OpenSpec 预执行脚本输出 `warning: failed to pull skill=openspec`，继续使用本地技能；仓库既有 `spec/` + `changes/` 布局按当前规范维护，不迁移目录。

## 边界

- 版本是 provider 声明的接口语义，不能证明实现正确，不能替代 ABI 检查或业务测试；默认 1.0.0 用于旧插件迁移兼容。
- 不兼容服务从普通 lookup/业务桥接隐藏，内部 publishedServices 仍可诊断。不会自动让插件启动失败；自动回滚仍取决于升级健康检查。
- 版本查找不会越过已选插件版本偷用旧版本。已取得的引用仍是快照，不得跨停止/升级缓存。
- 配置按接口 binary name；未知接口名称不主动加载，未匹配到的消费类型仍使用默认要求。配置须和实际消费接口一致。
- 本轮无 DB、外部支付网关或真实 NFS 集成验证；不处理 T8 事件持久化或 T10 进程外隔离。
