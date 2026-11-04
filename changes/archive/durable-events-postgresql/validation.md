# PostgreSQL durableEvents 验证

## 范围与开发中发现

- 基线 `43e8f48`，工作区初始干净；全量构建 95 reactor / 22 ABI 通过，日志 `/tmp/killbill-durable-baseline.log`。
- 独立 PostgreSQL 16 容器数据库，生产 bus_ext_events / history、adapter、固定信封、DAO 与 reaper。只增加受管 PostgreSQL JDBC 测试依赖；无新增生产依赖、bind/@Provides 或公共契约类型。
- 首轮 `python3 tools/validation/validate.py --skip-build --output /tmp/killbill-durable-first` 的 4 项 durable 测试失败。实际生产 DDL 的 search_key2 是 NOT NULL，PersistentPluginEvent 却返回 null，数据库报 SQLSTATE 23502；H2 通用队列表允许空值，旧测试未发现。日志 `/tmp/killbill-durable-first/postgres-durable-events.log` 与子 JVM 日志。
- 生产修复仅将 getSearchKey2 改为平台中性值 0，JsonIgnore 与信封格式不变；该值不表达业务租户授权。固定 UUID/topic/payload 保持原样。
- 诊断中恢复记录包含 REAPED 与 PROCESSED 两阶段。初版测试按 UUID 直接期望唯一历史行不符合生产 reaper 语义，修正为按 UUID+state 校验并要求两阶段存在，没有修改 reaper 或队列状态。
- 同一诊断数据库复跑 4 项通过：`/tmp/killbill-durable-searchkey-control.log`；之后的 search-key-null 单变量变异进一步验证非空约束守卫。
- 默认运行时回归最初误扫描新增 Test* PostgreSQL 类，4 项被无独立库守卫拒绝；日志 `/tmp/killbill-durable-regression-runtime.log`。模块默认 Surefire 未按 postgres 分组排除，因此改用标准 IT* 命名，专用验收显式选择 `ITPostgresqlDurableEvents`；未用 SkipException 隐藏失败，未更改默认 Maven 测试策略。

## 故障证据

| 场景 | 预期与观察 |
|---|---|
| 发布进程退出 | publish 返回后写 marker，再 halt(23)；DB AVAILABLE，新的消费 JVM 退出 0，历史 PROCESSED，身份不变 |
| 确认前崩溃 | handler 提交 UUID 唯一键业务记录后 halt(24)；DB IN_PROCESSING、无 PROCESSED；真实 claim 过期后新 JVM 恢复，REAPED+PROCESSED，同 UUID 两次 handler 调用、一份业务结果 |
| 重试 / 死信 | 瞬时失败 2 次尝试后 PROCESSED；永久失败 3 次尝试后 FAILED；缺失订阅为 FAILED，不能误算成功 |
| 写锁超时 | 仅锁本次库的外部队列表；发布连接 lock_timeout 得到 55P03，publish 显式失败，无误接收记录；释放锁后原 UUID 重发与消费成功 |

消息 class_name、UUID/topic/payload、DB 状态及子 JVM 退出码均由测试核验。子进程限时 25s，超时 finally 终止；连接配置在 0700 证据目录内、文件权限 0600，CI 只上传 JSON/log，排除连接配置。

## 最终结果

```bash
python3 tools/validation/validate.py --output /tmp/killbill-durable-final2
python3 -m unittest discover -s tools/validation -p 'test_*.py' -v
python3 /tmp/killbill-durable-regressions-final.py
```

首次完整入口 `/tmp/killbill-durable-final` 通过后，调整 IT 命名与显式选择，再以 `/tmp/killbill-durable-final2` 完整重跑通过。默认回归脚本使用 `.github/workflows/ci.yml` 中原有三组 Maven 模块和测试选择，顺序执行；未额外扩展 H2/MySQL 范围。

| 检查 | 结果 / 证据 |
|---|---|
| 全量构建 / ABI | 95 reactor / 22 ABI 通过；构建阶段跳过测试，`/tmp/killbill-durable-final2/build.log` |
| PostgreSQL | 账户 2 + 业务 1 + 队列停机 3 + durableEvents 4，10 项零失败、零跳过，`/tmp/killbill-durable-final2/report.json` |
| 真实 WAR | hello-world 测试支付 100.00 USD、重启恢复及两次停机日志门禁通过，同上 report |
| 默认运行时回归 | 241 项（平台模块 102 项，未误扫 PostgreSQL IT），`/tmp/killbill-durable-regression-final-runtime.log` |
| 适配器 / 插件 / REST | 60 + 6 项，`/tmp/killbill-durable-regression-final-adapters.log`、`/tmp/killbill-durable-regression-final-rest.log`；三组共 307 项零失败、零跳过 |
| 清理 | 最终入口创建的 4 个库均自动删除，retained_databases=[]；随后只读查询无 kb_accept_* 库残留 |
| Python 守卫 | 8 项通过，`/tmp/killbill-durable-python.log` |
| 变异守卫 | 9 项独立 PostgreSQL + 1 项 fast，10 项全部检出，`/tmp/killbill-durable-mutants/results.json` |
| 初轮诊断库清理 | 仅按初轮 report 的成功创建记录清理 3 个精确库名；原 failed 状态保留并追加清理记录，`/tmp/killbill-durable-diagnostic-cleanup.json` |

Java 命令使用 JDK 21.0.11、真实 Maven 3.6.3，DB 参数通过 surefireArgLine 传入，保留英文 locale 和四个 add-opens。初轮 --skip-build 明确不算全量构建。

## 变异验证

脚本 `/tmp/killbill-durable-mutations.py`：每项单独新建独立 PostgreSQL 库，只改一个条件；要求测试断言失败而非编译失败，finally 还原源码。诊断完成后仅删除该次明确创建的库，证据保留在 `/tmp/killbill-durable-mutants/`，汇总 `results.json`。

| 变异 | 对应检查 | 结果 |
|---|---|---|
| search-key-null | 恢复旧 search_key2=null，真实 DDL 拒绝发布 | 检出 |
| message-id | 发布时替换 UUID，原身份查询失败 | 检出 |
| payload | 发布时更改 payload，固定信封比较失败 | 检出 |
| strict-publish | 改走会吞失败的 post，锁超时未显式拒绝 | 检出 |
| handler-error | adapter 吞 handler 异常，重试/FAILED 条件失败 | 检出 |
| reaper | 夹具调度改为 1h，确认前崩溃后未按预算恢复 | 检出 |
| publisher-exit | 发布子 JVM 退出码改为 0，预期 23 不成立 | 检出 |
| consumer-exit | 消费子 JVM 退出码改为 0，预期 24 不成立 | 检出 |
| business-effect | 不提交业务效果，确认前崩溃证据缺失 | 检出 |
| fast-search-key-null | 恢复旧 null，默认 fast 契约断言失败 | 检出 |

## 边界

- Runtime.halt 验证进程突然退出，未重启 PostgreSQL 容器，不代表数据库服务器断电或主从切换验证。
- claim.time=1s、reapThreshold=2s、reapSchedule=20ms 仅用于独立夹具；没有更改平台默认配置。
- 测试唯一键演示业务幂等责任，不将队列宣称为 exactly-once。REAPED 历史与重放记录保留同 UUID，record_id 可不同。
- 原 WAR 验收仍验证业务支付/重启；本轮进程故障通过生产 adapter/队列子 JVM 实测，不声称在完整 WAR 中加载了新的故障测试插件。
- 七项静态门禁仍按规约跳过；Redis 仅 PING。MySQL、多节点/NFS、数据库服务端故障与远端 Linux CI 不在本轮通过范围。
