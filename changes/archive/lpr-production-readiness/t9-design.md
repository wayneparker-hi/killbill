# T9 安装完整性、签名与来源审计

## 契约

- `ArtifactSource` 的可选摘要改为 `sha256`（64 位 hex）；移除 `sha1` / `withSha1`，全仓调用迁移。节点命令遇到旧 `sha1` 明确拒绝，不能静默变成无校验安装。
- 安装总是计算实际 SHA-256；给定预期摘要时必须匹配。可配置要求每次安装提供摘要，默认保持可选。
- 可选签名策略由平台配置固定，节点命令不能覆盖。启用时完整读取并验证 JAR 每个 payload entry，拒绝无签名、篡改、部分签名、重复/歧义 entry、不可信或失效证书。签名元数据仅豁免标准 manifest / 签名块，其余 META-INF 内容仍须签名。
- 使用 JDK JarFile 和 PKIX 校验、配置的 PEM 信任证书；证书有效期、digitalSignature 与 codeSigning EKU 校验；离线校验不查询 OCSP/CRL。允许显式信任自签名签发者。所有 payload 须有共同的可信 signer；记录 signer 证书 SHA-256。
- 必须验证实际发布的同一份下载字节。签名模式下 descriptor 必须取自已签名 jar，外部 descriptor 仅允许完全一致，防止覆盖已认证入口。

- 签名安装拒绝 manifest Class-Path/JAR index；下载默认限 64 MiB，签名 JAR 展开默认限 256 MiB，内嵌 descriptor 限 1 MiB。先校验 ZIP 声明大小，再流式计数，避免无界分配；不承诺运行时沙箱。

## 审计

- SPI 增加不可变安装审计事件与 sink；manager/Core 不依赖具体存储。平台装配独立文件 adapter，在节点本地 audit 目录持久化一事件一文件，原子发布，卸载不会删除。
- 每次安装以 UUID 关联 STARTED、VERIFIED、PUBLISHED 或 FAILED；记录时间、pluginId/version、脱敏请求及重定向实际来源、预期/实际 SHA-256、descriptor SHA-256、签名要求、可信 signer 指纹及固定阶段/失败码。来源去除 userInfo、query、fragment；不记录凭据、远端响应体或原始异常文本。
- STARTED 与 VERIFIED 必须先持久化，审计失败不得发布。发布与审计跨两个存储不能承诺事务：发布成功而 PUBLISHED 记录失败时抛明确异常，保留已发布 artifact，不声称已回滚；VERIFIED 记录可用于人工核对。
- 通用库默认使用结构化日志 sink；平台生产装配使用文件 sink。file sink 并发安全、无覆盖、不自动清理；目录权限与日志收集由部署环境管理，不宣称防管理员篡改。

## 平台与兼容

- skife-config 新增 installation.requireSha256、installation.signature.required、installation.signature.trustedCertificates、installation.audit.dir、installation.maxArtifactBytes、installation.maxExpandedBytes；审计目录默认 install.dir/.install-audit（节点本地）。审计目录同时校验规范化与真实路径，拒绝符号链接别名指向插件/version。签名启用但信任材料缺失/无效时启动失败。
- 现有所有 `bind()` 与 `@Provides` 保留；installer provider 增加 verifier、审计 sink 的依赖，新 provider 装配策略与 adapter。管理入口仍为 nodesInfo。
- SHA-1 是有意的破坏性迁移；无历史外部调用可自动转换摘要。文档给出 sha256sum / shasum 计算和 keytool/jarsigner 使用方式。
- T6 HEAD `0ef8fcf` 全量构建作为对照，安装器、仓库、签名、平台命令与真实 TestKit 回归；新增守卫逐项破坏验证。完成后同步规格与 T9 任务，T7/T8/T10 保留待办，不归档整个变更。
