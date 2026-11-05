# 旧 worker 过期恢复验证

## 基线与范围

- HEAD `5afddca`，工作区初始干净。JDK 21、真实 Maven 3.6.3，全量基线 95 reactor / 22 ABI，日志 `/tmp/killbill-stale-baseline.log`。该基线编译平台原有 28 个测试源，完成于 10:53:52；首次测试源码改动在 10:56 以后。
- 复用指定共享 PostgreSQL 16 / Redis 容器，仅操作验收工具本次成功创建的独立库。普通测试不手改队列，不改角色、业务库或基础设施配置。
- 本轮先核验现有契约；不预设生产 fencing。新的两个 IT 依次测试迟到成功、迟到普通异常，均在 B 仍执行 handler 时释放旧 A。

## 首次实测

命令：`python3 /tmp/killbill-dual-focused.py /tmp/killbill-stale-first ITPostgresqlDurableEventsStaleWorker`。

2 项通过，`/tmp/killbill-stale-first/test.log`，库自动删除，retained_databases=[]。两场景都观察 A 的有效 claim、SIGSTOP 后 ps 状态 T、B 的 REAPED 归档与新物理记录、A 仍暂停且存活、SIGCONT 后非暂停状态。

| 场景 | 结果 |
|---|---|
| 迟到成功 | A 完成旧记录后，B 的新 IN_PROCESSING 完整快照不变；最终 REAPED(A) + PROCESSED(A) + PROCESSED(B)，两个 handler 调用，业务效果一份 |
| 迟到失败 | A 抛错触发旧记录的重试路径，B 的 record_id/owner/state/error_count/时间/信封不变；最终 REAPED(A) + PROCESSED(B)，旧记录不复活，业务效果一份 |

两 JVM 均正常退出 0，队列停止包含在途完成/重试排空；最终 active 为空、无 FAILED，两个节点各记录同 UUID 一次。

## 源码与语义

- `DBBackedQueue.reapEntries` 在事务中归档并重新插入；`QueueSqlDao.sql.stg` 的 INSERT 不带 record_id，新尝试获得新物理 ID。
- 完成路径 `moveEntriesToHistoryFromTransaction` 插入历史后，按旧 entry.recordId 删除 active；重试路径 `DBBackedQueueWithPolling.updateOnError` 也按旧 recordId 更新。新尝试不共享该 ID。
- 历史是尝试记录，迟到成功会追加旧 owner 的 PROCESSED，不代表业务副作用执行两次。不能再按 UUID 的 PROCESSED 数量单独判断重复效果。
- 现有至少一次语义允许重叠 handler。此次没有发现丢消息或旧完成/重试破坏新记录，尚无依据增加生产锁/选主/fencing。

## 单变量守卫验证

`python3 /tmp/killbill-stale-mutations.py`：7 项全部检出，`/tmp/killbill-stale-mutants/results.json`。每项仅改变一个条件、使用独立新库；必须是测试断言失败，编译失败不算检出。诊断后精确清库，源码 finally 还原。

| 变异 | 观察到的拒绝结果 |
|---|---|
| pause-not-applied | STOP 改为 CONT，实际状态非 T，暂停守卫失败 |
| resume-not-applied | CONT 改为 STOP，实际状态仍 T，恢复守卫失败 |
| handler-error-swallowed | 标记 failure 后吞异常，额外 PROCESSED 令历史断言失败 |
| missing-effect | 省略业务结果写入，预期一份而实际零份 |
| duplicate-delivery | 每节点记录同 UUID 两次，投递列表断言失败 |
| stale-ack-deletes-new | 临时将旧 PROCESSED 完成的删除目标变为下一物理 ID，B 活跃记录被误删，完整快照为空 |
| stale-retry-resets-new | 临时将旧重试 UPDATE 指向下一物理 ID，B 重领后 owner/state 已恢复但 errors=1，完整快照仍检出 |

最后两项实际修改 commons queue 的删除/重试目标，`mvn -pl killbill-commons/queue install -DskipTests=true` 编译安装变异制品，再运行对应一个 PostgreSQL IT 方法；不是只篡改测试返回值。编译日志在同一 mutants 目录。每项后还原源码与事先备份的本地 queue 制品；最终完整 clean install 再重建全部模块。正常验收不包含这些破坏动作。

## 最终验证

```bash
python3 tools/validation/validate.py --output /tmp/killbill-stale-final
python3 -m unittest discover -s tools/validation -p 'test_*.py' -v
python3 /tmp/killbill-stale-regressions.py
```

完整验收 `/tmp/killbill-stale-final/report.json` 为 passed，命令退出 0；6 个本次成功创建的库全部删除，retained_databases=[]。随后只读查询 `pg_database`，无 kb_accept_* 残留。

| 检查 | 结果 / 证据 |
|---|---|
| 全量构建 / ABI | 95 reactor / 22 ABI；构建阶段跳过测试，`/tmp/killbill-stale-final/build.log` |
| PostgreSQL | 账户 2 + 业务 1 + 队列停机 3 + 进程故障 4 + 双节点 2 + 旧 worker 2，共 14 项零失败、零错误、零跳过；同上 report |
| 迟到失败 | UUID `4da6a4e6-0d2c-4e29-94dc-e8721ba218ba`，active record_id 1 → 2；A 恢复并排空失败重试后，B 的记录 2 完整快照不变，最终 REAPED(A) + PROCESSED(B) |
| 迟到成功 | UUID `aa3ebc18-6e81-47ac-bf0c-934df21563c0`，active record_id 3 → 4；A 恢复并排空 ACK 后，B 的记录 4 完整快照不变，最终 REAPED(A) + PROCESSED(A) + PROCESSED(B) |
| 进程与业务效果 | 两场景均确认 A 实际为 T 后接管、CONT 后非 T，A/B 正常退出 0，active 为空，每节点投递一次，UUID 去重结果一份；`/tmp/killbill-stale-final/postgres-durable-stale.log` 与对应子 JVM 证据目录 |
| 真实 WAR | hello-world 测试支付 100.00 USD、重启恢复、两次队列停机日志门禁通过；同上 report |
| Python 守卫 | 8 项通过，`/tmp/killbill-stale-python.log` |
| 单变量变异 | 7 项全部检出，独立库均清理，见上文 results.json；最终 clean install 重建还原后的 queue 制品 |

默认回归沿用 `.github/workflows/ci.yml` 的三组模块与测试选择，顺序执行，退出码全部为 0。

| 相关回归 | 结果 / 日志 |
|---|---|
| 运行时 / 架构 / 隔离 / 泄漏 / TestKit | 241 项，平台模块 102 项，未误扫 PostgreSQL IT；`/tmp/killbill-stale-regression-runtime.log` |
| 适配器 / 插件 | 60 项，`/tmp/killbill-stale-regression-adapters.log` |
| REST | 6 项，`/tmp/killbill-stale-regression-rest.log` |

三组共 307 项零失败、零错误、零跳过。所有 DB 参数经 surefireArgLine、英文 locale 和四个 add-opens 传入；共享 DB 测试顺序执行。生产 Java、POM、公共 API、bind/@Provides 无改动。

## 边界

- 同机两个独立 JVM，暂停整个 A JVM，包含 worker/dispatcher/reaper；未覆盖仅卡 handler 而本机 reaper 继续执行、两 reaper 同时争抢或任意 SQL 交错。
- 本轮只释放 A 于 B 仍在途的窗口；没有将其表述为所有完成顺序的证明。
- claim=6s / reapThreshold=8s 为夹具设置；GMT、英文 locale、四个 add-opens 与父进程一致。未改平台默认值。
- 业务效果在进入屏障及暂停前已提交；恢复后测试迟到 ACK/重试，不覆盖恢复后才提交业务效果的顺序。UUID 唯一键夹具不能替代插件业务事务、支付外部系统的幂等或 fencing。
- 不覆盖两个真实 WAR、多主机、时钟偏移、网络分区、数据库服务端故障或远端 Linux CI。Redis 仅 PING；七项静态门禁按规约跳过。
