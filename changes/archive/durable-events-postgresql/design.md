# 设计

- 严格限制测试库名为验收工具生成的 kb_accept_UUID32，测试使用已初始化 schema。现有业务库、角色和容器不变。
- 使用 bus_ext_events / bus_ext_events_history、POLLING、external 配置及生产 PersistentPluginEventBus，不替换 DAO 或模拟消息投递。
- 父进程保存仅本次使用的私有连接配置和日志；子进程使用当前 JDK/测试 classpath 与四个 add-opens。Runtime.halt 明确跳过优雅关闭，退出码与 DB 状态共同构成故障证据。
- 发布故障点：postReliable 返回后 halt；消费故障点：UUID 幂等副作用事务提交后、handler 返回前 halt。通过新 JVM 和生产 reaper 等待真实 claim 过期，不手改队列状态。
- UUID、topic、payload 与信封 class_name 均校验；业务效果表仅为测试夹具，主键为消息 UUID，ON CONFLICT DO NOTHING 演示幂等。
- 写库故障使用独立库表锁与发布连接 lock_timeout，不停止数据库、不改角色、不改共享表。失败后释放锁并验证重发恢复。
- 全部等待有截止时间；异常时终止本次子进程，保留日志和库；按协议失败与超时失败分开记录。
