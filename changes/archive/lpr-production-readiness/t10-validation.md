# T10 验证记录

## 范围与基线

- T10 为研究 PoC：独立 `lpr-process` adapter + JDK-only `ProcessPlugin`，使用 JDK 21 Unix domain socket 与独立 JVM；不接入默认生产装配。
- 基线提交 `18dcc6d`，工作区初始干净；94 reactor 项目 / 22 ABI 门禁全量构建通过：`/tmp/killbill-t10-baseline-build.log`。
- 平台通过 test-source `ProcessAuthorizationPoc` 验证 `authorizePayment`、typed registry、ResourceRegistry、版本切换及父插件 ClassLoader GC；不连接外部支付网关。
- 保留所有现有 `bind()` / `@Provides`；仅添加平台 test-scope 依赖，没有默认业务实现分支或生产绑定。

## 负例守卫

逐项临时破坏一个保护条件，必须观察测试失败后还原。涉及 install 的变异同时还原本地 Maven 产物；最终 clean install 重新安装交付版本。共 24 项全部检出。

| 名称 | 结果 |
|---|---|
| request-budget | 检出 |
| protocol-version | 检出 |
| response-correlation | 检出 |
| response-status | 检出 |
| frame-status | 检出 |
| frame-count | 检出 |
| utf8-validation | 检出 |
| trailing-frame | 检出 |
| handshake-identity | 检出 |
| artifact-identity | 检出 |
| private-directory | 检出 |
| classpath-isolation | 检出 |
| remote-failure-status | 检出 |
| scope-expiry | 检出 |
| invocation-admission | 检出 |
| call-deadline | 检出 |
| interrupt-preservation | 检出 |
| parent-death | 检出 |
| socket-cleanup | 检出 |
| payment-result-validation | 检出 |
| api-boundary | 检出 |
| forced-termination | 检出 |
| java-options-isolation | 检出 |
| core-adapter-boundary | 检出 |

原始记录：`/tmp/killbill-t10-mutants.json`、`/tmp/killbill-t10-mutations.log` 与 `/tmp/killbill-t10-mutant-<名称>.log`。

## 复核与测试补强

- 初次 parent-death 变异未检出：空闲 worker 即使移除父进程 watcher，也能由 socket EOF 退出。补强为先确认 worker 进入不返回的插件调用，再 halt 父进程；重新变异后确认失败。该测试直接区分 watcher 与 EOF 两条退出路径。
- 继承 JVM 选项测试用单独父 JVM 注入三种 JAVA options，先确认对照父进程受影响，再验证 worker 配置不受影响、heap 上限仍生效；移除环境清理的变异被检出。
- 自审补齐 Selector 初始化失败时的 socket 清理；配置或注册失败也尽力关闭两个资源，并保留原始异常及 suppressed 清理异常。修改后重新全量 clean install，再运行完整目标回归。
- 架构、资源归属、并发准入、未知执行结果、协议预算与 ClassLoader 边界自审完成。无新增网络入口、自动重试、生产支付实现或 Core→adapter 依赖。

## 验证命令

使用 JDK 21 和真实 Maven `/Users/wayne/maven/apache-maven-3.6.3/bin/mvn`。`/tmp/killbill-fix-mvn` 设置 JAVA_HOME，并展开为 AGENTS.md 中的七个静态检查跳过参数。所有命令保留退出码，日志重定向而非管道。

```bash
/tmp/killbill-fix-mvn -T 1C clean install -DskipTests=true
bash /tmp/killbill-t10-final-regressions.sh
```

回归脚本与 `.github/workflows/ci.yml` 的三条命令一致；第一组增加 `lpr/lpr-process`，其他范围与 T8 相同。通过 `-DsurefireArgLine` 传入：

```text
-Xmx1024m -Duser.language=en -Duser.country=US
--add-opens java.base/java.lang=ALL-UNNAMED
--add-opens java.base/java.io=ALL-UNNAMED
--add-opens java.base/java.util=ALL-UNNAMED
--add-opens java.base/java.base=ALL-UNNAMED
```

测试串行执行，不连接共享开发数据库。完整回归包括架构约束、隔离、卸载、TestKit 端到端、已有运行时和业务目标测试；新增进程模块 19 项、平台授权 PoC 4 项。

## 实际边界

- 本机 macOS / JDK 21 实测；没有 Linux 生产部署、Windows 或真实支付网关验收。
- 真正子 JVM heap OOM、Runtime.halt、假死/卡住的 shutdown hook 已实测；只验证进程故障边界，不保证整机资源耗尽下存活。
- 进程是可信代码故障隔离，不是 OS 沙箱；无独立 UID/cgroup、子孙进程控制或恶意同 UID 攻击防护。
- RPC 每实例一个在途请求，无自动重启/重试。发送后失败结果可能未知；正式支付接入需要幂等键、状态查询/对账和完整业务 schema。
- 父进程骤停时 worker watcher 终止卡住调用，但私有 socket 目录可能遗留，需要运维清理；正常关闭和启动失败路径负责清理。
- 公共契约只含 JDK 类型；消息没有任意对象反序列化。worker classpath 仅必要运行时，插件库仍由 child-first loader 加载。
- stdout/stderr 丢弃，无生产诊断采集；无全局 worker 数量配额、supervisor、反向 Core API 或完整支付远程协议。
- 全量构建明确跳过测试；SpotBugs、dependency / dependency-scope / dependency-versions、duplicate-finder、enforcer、RAT 按项目规约跳过。不宣称全项目 slow/DB 集成测试通过。

## 最终结果（2026-11-04）

| 检查 | 结果 | 日志 |
|---|---|---|
| JDK 21 全量 clean install（跳过测试） | 95 reactor 项目、22 插件 ABI 门禁通过 | `/tmp/killbill-t10-final-build.log` |
| 三组目标回归 | 307 项，0 failure / error / skip；三条命令全部成功 | `/tmp/killbill-t10-final-regressions.log` |
| T10 新增测试 | 23 项（进程 19、平台 4），纳入上行回归 | 同上 |
| 负例守卫 | 24 项全部检出，源码与本地产物还原 | `/tmp/killbill-t10-mutants.json` |
| 差异与格式 | `git diff --check` 通过 | 提交前检查 |

T1–T11 已按各自验收范围完成；系统规格同步，整组变更归档。研究 PoC 完成不等于生产进程外支付网关已经落地。
