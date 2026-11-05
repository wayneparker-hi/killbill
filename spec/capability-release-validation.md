# Capability: 可复现发布验收

## Requirements

### Requirement: 复用基础设施并隔离数据

验收入口 SHALL 复用指定 PostgreSQL / Redis 容器；每次生成唯一 PostgreSQL 数据库名，只能初始化和清理本次成功创建的数据库。不得复用已有业务库或执行 Redis FLUSH。DDL 失败 MUST 显式失败。

#### Scenario: 全新数据库
- **WHEN** 执行验收入口
- **THEN** 依源码顺序创建 schema，成功后删除本次库；失败保留数据库和日志，既有数据库不变

### Requirement: 真实启动、业务闭环与恢复

验收 SHALL 部署实际构建的 WAR，使用生产 Guice 装配、LPR 目录和已有测试支付插件；HTTP 只监听本机。不得用生产支付网关或测试替换模块伪造启动信号。

#### Scenario: 业务与重启
- **WHEN** 创建租户、账户、订阅并由测试插件完成发票支付，再重启服务
- **THEN** 健康检查通过，账户/订阅/发票/成功交易可重新查询，发票余额为零，插件重新加载

#### Scenario: 同机双 WAR 消费、故障与重新加入
- **WHEN** 两个不同节点名的独立 WAR 共用本次 PostgreSQL 库与本机插件目录
- **THEN** 核验 PID/端口/插件与跨节点业务可见性、两节点同时持有有效 claim 的消费、确认前强杀 A 后不重启 B 的生产 reaper 接管，以及 A 新进程重新加入后的异步创建/轮询业务闭环
- **AND** 验收插件只通过公共 API 驱动生产链路；逐 UUID 检查物理记录、owner、投递和去重效果，异常清理全部 owned WAR；不推导多机/NFS、外部支付恰好一次或负载均衡自动重试保证

### Requirement: 检查结果可审计

入口 SHALL 保存阶段状态、执行命令、退出码、实际测试数量、失败/跳过与日志路径；构建与测试分开报告。子进程有启动/停止截止时间，异常或中断须清理本次服务。

#### Scenario: 数据库回归与失败传播
- **WHEN** 顺序运行指定 PostgreSQL slow 测试或某阶段失败
- **THEN** fork 收到数据库参数、英文 locale 和四个 add-opens；无测试/失败/跳过不得算通过，入口以非零状态退出

#### Scenario: 停机异常不可误报通过
- **WHEN** 真实 WAR 停服日志出现队列 timeout 或已完成记录尚未排空的告警
- **THEN** 验收失败、保留数据库与日志；进程已退出不能单独作为排空成功的依据

#### Scenario: 持久化插件事件的进程故障
- **WHEN** 独立 PostgreSQL 验收运行发布后退出、确认前崩溃、重试/死信与真实写锁超时测试
- **THEN** 子 JVM 故障退出码、消息身份、队列状态与幂等业务结果均须符合预期；保留子进程日志且不上传连接密码

#### Scenario: 双 JVM 竞争与接管
- **WHEN** 专用 PostgreSQL 双节点测试启动两个不同标识的 JVM，在同时在途时结束一个节点
- **THEN** 就绪/在途屏障、有效 claim、实际 owner、故障退出、跨节点重放和最终结果必须全部核验；超时或子进程提前退出不得算通过

#### Scenario: 旧 worker 在接管后恢复
- **WHEN** 对本次 owned JVM 发送 SIGSTOP/SIGCONT，在真实 claim 过期及 B 接管后恢复旧调用
- **THEN** 核验操作系统暂停状态、相同 UUID 的不同物理记录、迟到 ACK/重试后的新记录不变、业务去重及最终排空；超时或提前退出不得算通过

## 实现与验证对照

| Requirement | 实现 | 验证 |
|---|---|---|
| 隔离数据与 DDL | `tools/validation/infrastructure.py` | `ValidationGuards`、真实 PostgreSQL DDL 错误/事务回滚探针 |
| 真实启动与恢复 | `WarServer.java`、`http_smoke.py` | `validate.py` HTTP 业务与重启冒烟、`ValidationGuards` 失败结果守卫 |
| 结果与退出码 | `tools/validation/validate.py` | PostgreSQL 14 项目标测试、`ValidationGuards`、SIGTERM 实际进程探针 |
| 停机异常传播 | `Validation.stop_server` / `check_queue_shutdown` | `ValidationGuards.test_queue_shutdown_timeout_and_uncompleted_records_reject_acceptance` |
| 插件事件进程故障 | PostgreSQL 独立库与外部队列 | `ITPostgresqlDurableEvents`、`DurableEventsWorkerProcess` |
| 双节点竞争与接管 | 独立双 JVM、同库、生产 external bus | `ITPostgresqlDurableEventsCluster`、节点屏障 / owner / 退出码核验 |
| 旧 worker 恢复 | owned JVM STOP/CONT、真实 reaper、物理记录快照 | `ITPostgresqlDurableEventsStaleWorker` |
| 双真实 WAR | `war_node.py`、`war_cluster.py`、输出目录编译的验收插件 | 同时在途、真实强杀/接管、跨节点业务与重新加入；`ClusterGuards` |
