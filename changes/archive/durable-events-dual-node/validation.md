# 双节点持久化事件验证

## 范围

- 基线 `6194513`，工作区干净；JDK 21.0.11、真实 Maven 3.6.3。全量构建 95 reactor / 22 ABI 通过，日志 `/tmp/killbill-dual-baseline.log`。
- 生产代码、POM、公共 API、bind/@Provides 均无改动；只扩展测试、验收和相关规格文档。
- 同机两个独立 JVM，共用本次新建的 PostgreSQL 16 库和同一订阅，使用不同 CreatorName。生产 external bus、DAO、POLLING、adapter 和 reaper；没有模拟 claim/投递或直接改队列状态。
- 一批 12 个 UUID；claimed=1、queue.capacity=1、单 worker 限制测试预取。文件屏障只协调测试，节点日志各自记录投递；业务效果使用既有 UUID 唯一键夹具。

## 初轮实测与测试缺口

`python3 /tmp/killbill-dual-focused.py /tmp/killbill-dual-first`：2 项通过，原始日志 `/tmp/killbill-dual-first/test.log`、报告 `report.json`；测试库成功自动删除。

- normal-a / normal-b 的不同 PID 同时持有不同消息，DB owner 与各节点一致；初版租约有效断言的缺口见下文。释放后 12 个 UUID 各一次投递、12 条 PROCESSED，无 REAPED/FAILED。
- failover-a / failover-b 同时在途；A 提交业务效果后在确认前被强制终止，观察退出码 137；B 已运行且继续消费，原 UUID 被重放，REAPED owner=A、PROCESSED owner=B，业务效果仍一份。B 正常退出 0。
- 发布子 JVM halt(23) 是批量严格写入后、消费前的测试故障点；两种消费场景的文件标识和状态不共用。
- 增强对存活节点完整投递集合、故障节点确认前死亡和最终 claim 观察的断言后，`/tmp/killbill-dual-control` 两项再次通过。

## 时间口径对照

- 初轮 expired-claim 变异将正常场景 claim 改为 1ms，测试却通过；`/tmp/killbill-dual-mutants/expired-claim/test.log`。因此初轮两次通过不能作为租约有效性的最终证据。
- 初步改用 JDBC Timestamp 绑定当前时间后，同一变异仍通过；`/tmp/killbill-dual-mutants-final/expired-claim/test.log`。记录显示 expires=10:31Z、observed/db-now=02:31Z，相差约 8 小时，排除真实有效租约。
- 源码依据：parent POM 的 Surefire 固定 user.timezone=GMT；新建的独立 Java 子进程原先只传了 locale，继承 macOS 默认时区。JDBC 对 timestamp without time zone 的绑定/读取按各 JVM 时区转换，父子口径不一致。
- 测试修正：子 JVM 显式传递父 JVM 的 TimeZone，就绪信号核对实际时区；当前时间按生产 DAO 同样的 JDBC Timestamp 绑定，不将无时区列直接与带时区 CURRENT_TIMESTAMP 比较。保留到期/观察/数据库时刻证据；未修改生产时区逻辑、POM 或队列。
- 首轮临时变异脚本另有成功库重复 DROP 的辅助脚本错误，数据库已由 focused runner 删除，第二次 DROP 被拒绝；修正为创建记录减去已删记录，不将此错误算产品缺陷。

## 单变量守卫验证

`/tmp/killbill-dual-mutations.py` 的最终结果 `/tmp/killbill-dual-mutants-v3/results.json`：8 项全部检出。每项只改一个条件，使用独立新库；要求测试断言失败而非编译错误，finally 还原源码，诊断后按该次创建记录精确清库。

| 变异 | 实际拒绝条件 |
|---|---|
| node-identity | B 使用 A 的 CreatorName，就绪标识不符 |
| expired-claim | claim=1ms，实测 expires 早于 observed，租约守卫失败 |
| peer-not-killed | 省略终止 A，仍存活断言失败 |
| missing-effect | 省略业务效果提交，确认前效果断言失败 |
| reaper-disabled | reapThreshold=1h，恢复预算内无 PROCESSED |
| duplicate-delivery | 重复写投递记录，投递数量/唯一性断言失败 |
| message-id | 发布替换 UUID，固定批次信封不存在 |
| history-owner | 历史 owner 返回错误节点，归属断言失败 |

补充就绪时区守卫后，`/tmp/killbill-dual-timezone-mutation.py` 省略子 JVM 时区参数，实际 `Asia/Shanghai` 与父进程 `GMT` 不同，守卫检出；`/tmp/killbill-dual-mutants-timezone/results.json`。共 9 项有效变异，所有变异库清理、源码还原。早期未检出的变异保留失败诊断，不混入这 9 项结果。

## 最终验证

```bash
python3 tools/validation/validate.py --output /tmp/killbill-dual-final
python3 -m unittest discover -s tools/validation -p 'test_*.py' -v
python3 /tmp/killbill-dual-regressions.py
```

完整验收 `/tmp/killbill-dual-final/report.json` 为 passed；5 个成功创建的临时库全部删除，retained_databases=[]，随后只读查询无 kb_accept_* 残留。

| 检查 | 结果 / 证据 |
|---|---|
| 全量构建 / ABI | 95 reactor / 22 ABI；构建阶段跳过测试，`/tmp/killbill-dual-final/build.log` |
| PostgreSQL | 账户 2 + 业务 1 + 队列停机 3 + 进程故障 4 + 双节点 2，共 12 项零失败、零跳过；同上 report |
| 正常双节点 | 不同 PID、normal-a / normal-b 同时在途且真实 claim 未过期；A/B 各 5/7 次投递，共 12 个唯一 UUID，无 REAPED/FAILED；`postgres-durable-cluster.log` 与对应证据目录 |
| 故障接管 | A 的确认前 UUID `2a91c714-fa93-4645-bd8d-a7ab3f149e25`；观察时 02:36:42 UTC、到期 02:36:48 UTC。A 退出 137，B 退出 0；A/B 各 1/12 次投递，唯一重叠为原 UUID；REAPED owner=A、PROCESSED owner=B，业务效果一份 |
| 真实 WAR | hello-world 测试支付 100.00 USD、重启恢复、两次队列停机日志门禁通过；同上 report |
| Python 守卫 | 8 项通过，`/tmp/killbill-dual-python.log` |
| 单变量变异 | 8 + 1 项全部检出，独立库均清理，见上文 results.json |

默认回归使用 `.github/workflows/ci.yml` 中原有三组 Maven 模块与测试选择，顺序执行，退出码全部为 0；没有扩大或改变默认 DB 测试策略。

| 相关回归 | 结果 / 日志 |
|---|---|
| 运行时 / 隔离 / 泄漏 / TestKit | 241 项，平台模块 102 项，未误扫 PostgreSQL IT；`/tmp/killbill-dual-regression-runtime.log` |
| 适配器 / 插件 | 60 项，`/tmp/killbill-dual-regression-adapters.log` |
| REST | 6 项，`/tmp/killbill-dual-regression-rest.log` |

三组共 307 项零失败、零错误、零跳过。所有 DB 参数经 surefireArgLine、英文 locale 和四个 add-opens 传入；测试顺序执行，无共享数据库并行测试。

## 边界

- 两个 JVM 在同一主机，不等同于两台主机或两个完整 WAR；未测试网络分区、NFS、数据库服务端故障或远端 Linux CI。
- 正常场景的 claim=30s / reapThreshold=40s；故障场景为 6s / 8s，仅夹具覆盖，不修改平台默认配置。
- 测试没有证明任意 SQL 交错、调度公平性或性能上限。正常场景每 UUID 一次投递是该有效租约场景的观察，不提升为 exactly-once 保证。
- 未验证旧 worker 在租约失效后仍继续运行的 fencing；进程被强制终止是故障场景的明确前提。跨节点重放会再次执行 handler，插件仍须实现业务幂等。
- 仅清理报告中本次成功创建的精确数据库；不停止共享 PostgreSQL/Redis、不改角色或已有业务库。Redis 仅 PING；七项静态门禁按规约跳过。
