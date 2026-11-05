# 同机双 WAR 部署验收阶段

归属 product-assurance 的部署与可复现证据阶段；覆盖 PA-04/PA-08 的同机子集，不替代 PA-01/02/03 或真实多机验收。本阶段内实现、负例、回归和证据一起交付，不为每个场景另建 change；总体规划继续开放。

## 设计与完成标准

- 两个独立 JVM 部署同一真实 WAR/web.xml，生产模式和 Guice 装配。共用本次独立 PostgreSQL 库与本机插件目录，端口及解压目录各自独立；节点名显式不同。
- hello-world 负责既有本地测试支付；单独的验收插件通过公共 PluginContext 注册 Servlet/持久订阅，在 HTTP/handler 中使用平台 DataSource。插件只在工具输出目录编译、打包和安装，不进入生产 WAR 或 reactor。
- 持久化消息由 HTTP→插件→生产 durable adapter 写入；handler 在确认前提交 UUID 唯一效果、记录每节点投递并进入有界文件屏障。只有夹具表和屏障由工具创建；不手改生产队列表。
- 正常场景核对两个不同 PID/端口/节点同时持有不同 UUID 的有效 claim；释放后每消息一条 PROCESSED，无 REAPED/FAILED，效果和投递均逐 UUID 核对。
- 故障场景两个节点均在途，强杀 A 并确认退出 -SIGKILL；B 不重启，真实 reaper 回收 A 的过期物理记录并重放。核对 REAPED owner、新物理 ID、有效 claim、A/B 投递及效果一份后释放 B，最终无 active/FAILED。
- A 重新启动须换 PID；通过真实 REST 在两节点交叉验证既有租户/订阅/发票/交易，并在故障后、重新加入后创建新业务。不是负载均衡器自动重试或外部支付恢复证明。
- 所有本次 WAR 在异常路径都须清理；正常停机检查队列日志，故障注入单独记预期退出。独立库仅在全阶段成功后删除，失败保留日志/库，不停止共享容器。
- 用单变量变异验证身份/同时在途/重放/结果/故障退出等关键断言，编译失败不算检出。运行完整验收、相关回归后写入本文件结果；不虚构远端 CI。

## 任务

- [x] 核实源码、工作区及全量构建基线
- [x] 双 WAR 进程管理、插件夹具与三阶段部署验收
- [x] 有效负例与异常清理验证
- [x] 完整验收、相关回归、文档与提交证据

## 结果

保留初始 proposal.md、tasks.md 及索引中的总体规划，补充本阶段记录，其他任务未计为完成。

### 基线与初轮诊断

- 基线 HEAD `4cc4c2d`；初始未提交内容只有总体规划与索引。JDK 21、真实 Maven 3.6.3 全量构建 95 reactor / 22 ABI，`/tmp/killbill-war-baseline.log`；构建跳过测试和规约指定的七项静态检查。
- 首轮 `/tmp/killbill-war-first`：夹具只带管理员认证，插件路径返回 401。增加独立租户后 `/tmp/killbill-war-control` 返回 404；按 `JaxrsResource.PLUGINS_PATH` 修正为 `/plugins/cluster-probe`。两者均是验收代码错误，进程正常退出 143，报告保留，诊断后精确删除对应库。
- `/tmp/killbill-war-control2`：两真实 WAR 已启动，A 的 `callCompletion=true` 创建请求返回 408。只读数据库证明一份订阅、一条金额 100 的发票项已创建；`bus_events_history` 中 EffectiveSubscription、InvoiceCreation、InvoicePaymentError 的 owner 都是 B。
- 源码：`KillbillEventHandler.activeWaiters` 是每个 JVM 的内存列表，`SubscriptionResource.EntitlementCallCompletion` 只等待本机完成事件；408 不证明创建失败。单节点默认同步冒烟保持不变；双节点采用 `callCompletion=false` 加有界轮询最终状态。不引入分布式等待器，也不把异步通过当作同步完成修复。
- 对照 `/tmp/killbill-war-control3` 只改变业务流的 callCompletion 参数，保持 WAR/节点/数据库初始化与队列配置；异步业务及跨节点查询通过，随后在正常消息屏障处拒绝过期 claim，未将该轮整体记为通过。

- `/tmp/killbill-war-control3` 暴露夹具错误：queue.capacity=1 仍允许 dispatcher 预取，active 的 IN_PROCESSING 总数不等于正在执行的 handler 数。原等待条件“总数恰好 2”错过初始有效窗口，直到 reaper 后才观察到过期记录，守卫正确拒绝。改为同时核对 entered 屏障对应的两条实际在途记录，不放宽租约断言；control2/control3 均正常停止全部 WAR，诊断后按报告精确清库。

### 首次完整部署场景通过

`python3 /tmp/killbill-war-focused.py --skip-build --output /tmp/killbill-war-control4` 退出 0，1 个库自动删除。正常 8 UUID，双节点有效 claim；故障接管与重新加入、4 组业务及交叉读取通过。该次使用 2 条故障消息。

`/tmp/killbill-war-control5` 在故障场景扩展为 6 条消息，覆盖被杀节点预取但尚未执行的记录，并在重新加入后复核投递不增加；全部通过，库自动删除。参数仅为夹具 external bus：claim=15s、reapThreshold=20s、单 worker，两个 WAR 统一 GMT。不是生产默认配置或容量数据。

### 关键保护条件负例

`python3 /tmp/killbill-war-mutations.py`，每项仅改变一个条件，独立创建库并部署实际 WAR；要求夹具编译成功后由运行断言/截止时间拒绝。成功的变异检出均将 owned 进程全部结束，并诊断后按报告精确清库，源码 finally 还原。结果 `/tmp/killbill-war-mutants/results.json`。

| 条件变异 | 拒绝证据 |
|---|---|
| missing-effect | 删除效果 INSERT，Wrong durable effects |
| duplicate-delivery | 重复写投递，Normal delivery duplicated/missing |
| same-node-name | B 实际使用 A 的节点名，Wrong WAR identity |
| expired-claim | claim 改 1ms，Invalid simultaneous claims |
| wrong-fault-exit | SIGKILL 改 SIGTERM，确认前 handler 未完成，crash wait 在 5s 截止拒绝；清理后退出 143 也被正常停机守卫拒绝 |
| reaper-disabled | reapThreshold 改 1h，45s 恢复窗口无重放，入口超时失败 |

辅助变异脚本首次对 wrong-fault-exit 的文本替换匹配两处，断言停止且源码还原，未执行该变异；修正为限定 crash 方法。重跑时初始 reason 匹配器预期 Wrong fault exit，而实际先遇到 TimeoutExpired，因此记为 caught=False；原始结果保留诊断说明后，依据实际超时拒绝归类为检出，不将其声称为退出码检查已执行。退出码保护条件另以 Python 守卫及变异验证。

另对 Python 守卫临时关闭 crash 退出码检查、正常停机失败检查，以及只清理最后一个节点，3 项均由对应 unittest 断言检出，源码还原；`/tmp/killbill-war-guard-mutants.json`。正常停机变异初版因夹具日志不存在而 FileNotFoundError，未计检出；补足干净停机日志后重跑，以 assertRaises 未捕获异常的断言失败确认实际守卫作用。

13 项 Python 守卫全部通过，`/tmp/killbill-war-python-final.log`。

### 最终验收与回归

```bash
python3 tools/validation/validate.py --output /tmp/killbill-war-final
python3 -m unittest discover -s tools/validation -p 'test_*.py' -v
python3 /tmp/killbill-war-regressions.py
```

三个命令均退出 0。完整入口 report.json 为 passed；7 个本次库全部自动删除，retained_databases=[]；只读查询 pg_database 无 kb_accept_* 残留。所有变异库和此前失败的聚焦测试库均已诊断后精确删除。没有停止共享容器、修改已有业务库/角色或 Redis 键。

| 验证 | 结果 / 原始证据 |
|---|---|
| 全量构建 / ABI | 95 reactor / 22 ABI；构建跳过测试，七项静态门禁按规约跳过；`/tmp/killbill-war-final/build.log` |
| PostgreSQL 回归 | 14 项，零失败/错误/跳过；`/tmp/killbill-war-final/report.json` 与 postgres-*.log |
| 单 WAR 原有验收 | 100.00 USD 测试支付、重启恢复、两次正常停机门禁通过；同上 report |
| 正常双 WAR | 8 UUID，A/B 的 PID/端口/节点名不同，两个 entered 对应的 claim 均有效；8 次投递/效果/PROCESSED，无 REAPED/FAILED；report cluster-normal |
| 故障接管 | A PID 59023 退出 -9，B PID 59083 不变。UUID 65374cf0-40c7-491b-81e3-4e6abf25ce97 的 active record_id 27 → 33，新 owner=B；租约从 04:16:54 UTC 到重放后 04:17:14 UTC。最终 6 条消息都由 B 完成，A 的 3 条尝试被 REAPED（含 2 条预取），实际 handler 仅原 UUID 跨节点调用；效果一份 |
| 重新加入与业务 | A 新 PID 59618，B PID 不变；4 组本地测试支付均为 100.00 USD，订阅/账单/交易跨节点可读；已完成消息的投递未增加。两个存活 WAR 正常停机，无队列告警 |
| 默认运行时回归 | 241 项（平台 102），包括架构约束、ClassLoader 隔离/泄漏与 TestKit；`/tmp/killbill-war-regression-runtime.log` |
| 适配器 / 插件 / REST | 60 + 6 项；`/tmp/killbill-war-regression-adapters.log`、`/tmp/killbill-war-regression-rest.log` |
| Python 与负例 | 13 项守卫，6 项实际 WAR 负例 + 3 项守卫变异，详见上文；未将辅助脚本错误计为通过 |

307 项默认回归零失败/错误/跳过；同库 DB 测试顺序执行，参数经 surefireArgLine、英文 locale 和四个 add-opens 传入。5 次 WAR 启动中 1 次为预期强杀，4 次正常停机，owned PID 全部结束。现有 CI 已调用相同验收入口，本轮未执行远端 Linux CI。

阶段自审：验收逻辑、生成插件和文档均符合当前生产调用链；生产 Java/POM/API/bind/@Provides 无修改，AGENTS.md 与 CLAUDE.md 不变，未引入 .workbuddy。规格已映射到可执行入口；本阶段完成后提交，总体 product-assurance 保持开放。

### 仍未覆盖

同机本地文件系统不代表多机/NFS 或网络/数据库服务端故障；不验证负载均衡自动重试、故障期间持续请求的可用性 SLO、在途支付升级/回滚、外部网关资金效果、长期容量或生产安全边界。UUID 效果表只演示该夹具的幂等责任；hello-world 交易数据保存在进程内，跨节点校验读取的是 Core 持久化结果。独立计价、真实网关及其沙箱、长稳与用户收益仍属于总体规划的未执行项。
