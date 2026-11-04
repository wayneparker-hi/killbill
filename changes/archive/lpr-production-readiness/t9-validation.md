# T9 插件安装安全验收

基线：`0ef8fcf`（T6）。本次完成 SHA-256、可选完整 JAR 验签和来源审计；T7/T8/T10 保持待办。

## 实现与验证

| 范围 | 实现 | 可执行验证 |
|---|---|---|
| SHA-256 契约 | `ArtifactSource.sha256/withSha256`，64 位 hex 校验与规范化；旧 sha1 命令明确拒绝 | `TestPluginInstaller`、`TestPluginInstallAudit`、`TestPluginNodeCommandListener` |
| 宿主不可降级策略 | skife-config / `ArtifactVerificationPolicy`；必填摘要、签名要求与 PEM 信任锚 | `TestPluginRuntimeConfig`、`TestPluginRuntimeModule`、节点命令策略测试 |
| 完整签名与 PKIX | `JarSignatureVerifier`；真实 keytool/jarsigner，全部 payload 共享可信 signer | `TestJarSignatureVerifier`：可信自签名/CA 链、篡改、部分签名、无签名、不可信/过期/错误 EKU、重复/歧义 entry |
| 签名内容边界 | 已签名 plugin.yaml 与发布字节一致；禁止外部覆盖、manifest Class-Path 和 JAR index | descriptor/外部类路径测试；`TestSignedPluginInstallation` |
| 大小边界 | file/HTTP 包 64 MiB，签名 JAR 展开总量 256 MiB，内嵌 descriptor 1 MiB；配置可调整前两项 | 文件与 chunked HTTP 超限、恰好边界、签名展开与 descriptor 超限测试 |
| 审计 SPI/adapter | `PluginInstallAudit` / `PluginInstallAuditSink`；独立 filesystem adapter 原子发布事件文件 | `TestFileSystemPluginInstallAudit`：两个 sink 并发 24 项完整记录、不覆盖、目录不可用显式失败 |
| 审计与发布边界 | STARTED → VERIFIED → PUBLISHED 或 FAILED；UUID、摘要与 signer 指纹；发布前失败关闭，发布后明确已发布 | `TestPluginInstallAudit`：失败阶段、卸载保留、审计前后失败及文件内容验证 |
| 来源脱敏 | requested/resolved URI 去 userInfo/query/fragment；节点失败日志不含原事件/异常原文 | 真 HTTP 重定向及非法来源失败测试 |
| 审计目录保护 | 规范化路径及真实路径均不得落入仓库插件/version | `TestPluginRuntimeModule`：直接路径及符号链接别名拒绝 |
| 架构与端到端 | Core 仅依赖 SPI，平台装配策略/文件 adapter；签名包经共享缓存、ClassLoader、Registry | `TestArchitecturalConstraints`、`TestSignedPluginInstallation`：双节点安装/升级/回滚、拒绝未签名候选并保留旧路由 |

既有 `bind()` / `@Provides` 无删除；installer provider 增加 policy / audit 依赖，新增两个单例 provider。无生产依赖/POM/模块新增。SHA-1 API 为有意破坏性迁移，调用方须重算摘要，不能把旧摘要填入新字段。

## 负例守卫

逐项临时破坏条件，确认对应测试断言失败后还原，共 **22 项**：

1. 绕过 SHA-256 比较。
2. 绕过必填摘要策略。
3. 接受格式错误摘要。
4. 签名验证异常改为放行。
5. 跳过 unsigned payload。
6. 同时关闭 signer 链和信任锚的有效期检查。
7. 关闭 codeSigning EKU 检查。
8. 允许外部 descriptor 覆盖。
9. 请求来源不脱敏。
10. 忽略重定向实际来源。
11. 跳过 VERIFIED 审计。
12. 隐藏发布后审计失败的已发布状态。
13. 接受旧 sha1（即使同时提供 sha256）。
14. 关闭审计目录位置保护。
15. 接受 manifest Class-Path。
16. 接受 JAR index。
17. 忽略签名包展开上限。
18. 忽略 descriptor 上限。
19. 忽略安装包读取上限。
20. 接受无效大小配置。
21. 关闭审计目录真实路径保护。
22. Core classpath 注入审计 adapter 临时类。

最后一项删除注入的源码及编译产物；最终 clean 构建清除其他变异编译产物。另有一次关闭 JarFile 内建验签的实验仍被「缺少可信 signer」拒绝，属于冗余保护，不计入检出数。日志：`/tmp/killbill-t9-negative-summary.log`（前 3 项及冗余实验）、`/tmp/killbill-t9-negative-more-summary.log`（11 项）、`/tmp/killbill-t9-extra-guards-summary.log`（8 项）、各 `negative-*.log`。

## 构建与测试

全量构建 **94 个 reactor 项目成功、22 个插件 ABI 门禁通过**（5 分 34 秒）；构建跳过测试。按 CI 三组命令独立运行 **252 项目标回归，0 失败、0 错误、0 跳过**。

| 范围 | 数量 |
|---|---:|
| Core（含架构约束） | 42 |
| Lifecycle adapter | 3 |
| ClassLoader 隔离与卸载 | 8 |
| Repository / Audit adapter | 12 |
| TestKit（含签名双节点用例） | 31 |
| ABI validator | 10 |
| 平台 plugin-runtime | 76 |
| hello-world 迁移 | 4 |
| SPI / YAML / EventBus / Installer | 50 |
| 邮件 / 自定义工厂 / 支付重试 / 目录插件 | 10 |
| 节点 JSON / 更新时间 / 兼容 | 5 |
| 集群 REST 响应 | 1 |
| **合计** | **252** |

全量构建日志：`/tmp/killbill-t9-final-build.log`；回归脚本 `/tmp/killbill-t9-final-regressions.sh`，日志 `/tmp/killbill-t9-final-regressions.log`。回归与 CI 一致，测试 JVM 保留四个 add-opens 与英文 locale。

- 变更前全量构建成功：`/tmp/killbill-t9-baseline-build.log`。
- JDK 21、真实 Maven 3.6.3，使用 AGENTS 规定的全量 `clean install -DskipTests=true`；约定的 SpotBugs、dependency、scope、versions、duplicate-finder、enforcer、RAT 跳过，ABI/POM 排序启用。构建本身不代表测试通过。
- CI 回归配置加入签名与审计用例；YAML 解析及全部 shell 步骤 `bash -n` 通过，未触发远端 Actions。
- AGENTS 保持通用规范；无 `.workbuddy` 目录/文件。

开发阶段失败及处理：

| 命令 / 日志 | 输出 | 处理与影响 |
|---|---|---|
| 安装器定向 `mvn test`，`/tmp/killbill-t9-first-tests.log` | URI 字符串 `file:///` 与规范化 `file:/` 比较失败 1 项 | 按 URI 语义比较后通过；无生产功能回归 |
| OpenSpec status / instructions | `No OpenSpec directory found` | 仓库使用既有 `spec/` + `changes/` 布局，按当前规范手工同步，不迁移目录 |

## 边界

- 安装默认保留摘要/签名可选兼容模式；生产可同时开启强制策略。只覆盖安装器入口，人工写仓库后直接发现/启动不在验签保证内。
- 离线 PKIX 不查询 OCSP/CRL，不保证撤销状态；按当前证书有效期检查，不接受历史签名时间豁免过期。
- 文件审计经过 flush/force/原子 rename，但不承诺断电时目录元数据持久化，不具备防管理员篡改或保留策略；部署须限制权限、集中收集。
- 发布与结果审计跨存储，无事务保证：后者失败会明确报告 artifact 已发布并保留文件，可用 VERIFIED 记录人工核对。
- 大小限制约束安装读取，不构成插件运行时沙箱或进程隔离。
- 使用真实 JDK 签名工具及本地 loopback HTTP，未验证真实 NFS、DB、外部支付网关或远端证书撤销服务。
