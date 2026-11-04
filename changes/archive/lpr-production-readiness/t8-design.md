# T8：持久化插件事件与背压

## 选择与边界

- 保留 `EventBus` 同步内存语义；新增 JDK-only `DurableEvent`、`DurableEventHandler`、`DurableEventBus` 与 SPI `DurableEventBusFactory`。Context 懒创建插件作用域，自动交给 ResourceRegistry。
- 明确目标 pluginId + 稳定 subscriptionId；一条记录对应一个逻辑消费者。重启/升级不用插件私有 Class 对象寻址；topic/payload 为文本，UUID 用于业务幂等。
- Kill Bill adapter 使用 external PersistentBus，固定平台信封；严格入口不得吞写入异常。未初始化/内存模式拒绝。不修改旧 post 的调用方行为。
- 订阅作为 ServiceRegistry capability 注册，复用 staging/selection/health 路由；一次只调用一个可见 handler。无 handler 时抛异常交给队列重试，不能 ACK。
- Scope/Subscription 关闭清除 handler、loader、registration 引用；注册在 scope 锁内，取消不影响其他版本。调用快照允许执行完，finally 恢复 TCCL。
- 配置显式开启（默认关闭）。适配器 START_PLUGIN 注册、STOP_PLUGIN 禁止新发布与消费（保留只会拒绝的宿主 listener，直到 STOP_BUS 停止，避免无订阅时静默 ACK），与现有 bus INIT/START 顺序一致；不开额外 worker。
- 所有共享 externalBus 数据库的消费节点必须同时启用该能力；混合启停配置不支持，未注册 adapter 的节点会按原总线规则忽略未知消费者。部署时先配置全体节点，再发布业务消息。

## 限制与失败语义

| 范围 | 处理 |
|---|---|
| 单节点并发数据库发布 | semaphore 非阻塞准入；满额显式异常 |
| 单节点执行中 handler | semaphore 非阻塞准入；满额抛异常，由 PersistentBus 重试 |
| 信封 | 用队列相同 mapper 计 UTF-8 字节，配置 1..2048；不改 DDL |
| 失败 | 队列 max.failure.retry；耗尽进入实际 historyTableName FAILED（平台默认 bus_ext_events_history） |
| 数据库 backlog | 不承诺总量有界，不做非原子 count-then-insert |
| 消费 | 至少一次、无顺序保证；消息 UUID 幂等；离线超过重试预算也进入死信 |
| 死信运维 | 历史表按 class_name/UUID 查 FAILED；人工确认幂等后通过 API 重新发布 |

## 装配与验证

现有 bind/@Provides 全保留；仅新增 DurableEventBusFactory provider 与 adapter 单例，lifecycle/context 增加兼容构造重载。Core 只引用 API/SPI；第三方序列化和数据库代码只在平台。

基线：94 reactor、22 ABI 门禁通过（/tmp/killbill-t8-baseline-build.log），跳过测试及 AGENTS 列出的静态检查。
验证包括隔离数据库的重启/重试/FAILED、严格失败/准入、真实插件 ClassLoader TCCL/GC、升级回滚、架构回归与变异守卫；最终记录见 t8-validation.md。
