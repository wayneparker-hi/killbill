# 归档：发布验收与真实业务闭环

- **状态**：本轮范围完成
- **日期**：2026-11-04
- **规格**：[发布验收](../../../spec/capability-release-validation.md)
- **使用**：[release-validation.md](../../../docs/development/release-validation.md)
- **验证**：[validation.md](validation.md)

## 结果与后续

95 reactor / 22 ABI、3 项真实 PostgreSQL slow、7 项 Python 守卫、17 项变异守卫与真实 WAR 支付/重启恢复通过。既有基础设施保持运行，本次临时库已清理。

后续优先调查停服队列超时告警及偶发平台服务发布告警，再扩展 PostgreSQL durableEvents 故障恢复、双节点和 Linux CI 结果核验。Redis 当前只检查 PING；静态门禁仍按项目规约跳过。

## 发布验收与真实业务闭环

### Why

95 模块构建和 307 项目标回归已通过；尚缺复用本地 PostgreSQL/Redis 的独立数据库、生产 WAR 启动与重启后的业务验收入口。使用指南中的 bin/start-server、bin/db-helper 已不存在。

### What Changes

- 仓库内 Python 标准库验收入口，复用现有 Docker PostgreSQL / Redis。
- 每次创建唯一数据库，按源码 DDL 初始化，禁止指定或清理既有数据库。
- 从全量构建产物启动真实 WAR，加载现有 hello-world 测试支付插件，HTTP 验证租户、账户、订阅、发票和支付，再重启验证持久化。
- 顺序执行选定 PostgreSQL slow 测试，严格核实执行数量与 skip；生成机器可读报告和独立日志。
- 更新使用文档；不改生产业务、默认平台装配、共享基础设施配置。

### Impact

新增 tools/validation 与发布验收规格；复用既有 Java/Maven 依赖，不新增依赖版本。本轮 Redis 仅 PING，缓存与分布式时钟不启用；Linux/MySQL、双节点和静态门禁恢复另行验收。

---

## Tasks

- [x] 核实基础设施、源码入口及建立全量构建基线
- [x] 需求规格、提案与资源隔离设计
- [x] 唯一数据库、源码 DDL、可复现命令及结构化报告
- [x] 生产 WAR / LPR 支付插件 HTTP 业务链路与重启验证
- [x] PostgreSQL 目标 slow 测试、失败/清理守卫实测
- [x] 最终验收、文档同步、自审、归档与提交
