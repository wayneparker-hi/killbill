# 归档：通知队列停机结果与完成排空

- **状态**：本轮范围完成
- **日期**：2026-11-04
- **规格**：[通知队列](../../../spec/capability-notifications-queues.md)、[发布验收](../../../spec/capability-release-validation.md)
- **验证**：[validation.md](validation.md)

## 结果与边界

修复逻辑队列停用误报及完成/重试批次提前退出；95 reactor / 22 ABI、6 项 PostgreSQL、真实 WAR 支付与重启、两次停机日志门禁、34 项 H2 支持范围、307 项运行时/插件/REST 回归、8 项 Python 守卫及 8 项变异检查通过。

默认测试受 MySQL ARM64 夹具缺包影响；H2 明确排除硬编码 MySQL 的 Demo。正常排空后重启不重放已完成通知；真实超时恢复可能再次执行 handler，保持至少一次语义。远端 Linux CI 尚未运行；后续优先 PostgreSQL durableEvents 故障恢复。

---

# 通知队列停机结果与完成排空

## Why

真实 WAR 停机在毫秒内打印 8 条队列 timeout。源码中共享调度器在非最后一个队列停用时返回 false，调用方按超时处理；完成线程也可能在停止标记后留下多批次完成记录。需要可重复对照，区分错误告警、实际超时和持久化恢复。

## What Changes

- 修正非最后一个逻辑队列成功停用的返回值，保留实际关闭失败传播。
- 验证并修复完成线程退出前对完成/重试队列的排空。
- 增加有界、门闩驱动的测试和 PostgreSQL 验收；真实 WAR 停机检查不再忽略队列异常。

## Impact

仅涉及 commons queue、验收工具、CI 与规格文档，不改生产装配、插件生命周期或外部基础设施。不承诺崩溃场景的 exactly-once 或任意阻塞 handler 的无损强制关闭。

---

# Tasks

- [x] 全量构建基线、日志与源码对照
- [x] 停止返回值、完成排空的失败复现与最小修复
- [x] 真实超时、在途完成、重启与重复执行验证
- [x] 新增守卫逐项破坏确认检出
- [x] 全量构建、PostgreSQL / WAR 验收及相关回归
- [x] 同步文档、归档、自审与提交
