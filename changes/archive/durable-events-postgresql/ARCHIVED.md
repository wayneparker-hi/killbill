# 归档：PostgreSQL 持久化插件事件故障恢复

- **状态**：本轮范围完成
- **日期**：2026-11-04
- **规格**：[事件总线](../../../spec/capability-event-bus.md)、[发布验收](../../../spec/capability-release-validation.md)
- **验证**：[validation.md](validation.md)

## 结果与边界

修复持久化信封 search_key2=null 与生产外部队列表非空约束的不兼容，使用中性值 0。发布后退出、确认前崩溃、重试/死信及真实 PostgreSQL 写锁失败进入可重复验收。

95 reactor / 22 ABI、10 项 PostgreSQL、真实 WAR 支付与重启、307 项运行时/插件/REST 回归、8 项 Python 守卫及 10 项变异检查通过。临时数据库均已精确清理。

保留至少一次语义；UUID 唯一键只演示业务去重。未验证数据库服务端故障、双节点竞争消费或远端 Linux CI；下一优先项为双节点消费与故障接管。

---

# PostgreSQL 持久化插件事件故障恢复

## Why

durableEvents 已有固定信封、严格写入及 H2 重启/重试测试，但真实 PostgreSQL 上发布进程退出、消费确认前崩溃和写锁失败缺少可重复验证。

## What Changes

- 使用验收工具创建的独立 PostgreSQL 数据库，测试生产 external bus 表、DAO、reaper 和 durable adapter。
- 子 JVM 在发布成功后、业务副作用提交后分别 halt；父测试验证退出码、持久化状态及新 JVM 恢复。
- 验证瞬时/永久失败、缺失订阅的重试与 FAILED 历史，以及真实 lock_timeout 的严格发布失败。
- 修复真实外部队列表 search_key2 非空约束与持久化信封返回 null 的不兼容。
- 纳入同一发布验收入口和 CI，保存独立日志，失败保留证据，成功精确清库。

## Impact

优先只增加测试与验收能力；若对照发现生产缺陷，再按最小范围修复。无公共 API 变更、真实网关调用或共享基础设施配置改动；不承诺全局顺序或 exactly-once。

---

# Tasks

- [x] 全量构建基线与现有契约核对
- [x] 独立 PostgreSQL 存储夹具与发布/消费子 JVM
- [x] 发布后退出、确认前崩溃、重试/死信和真实写锁失败验证
- [x] 新增保护条件逐项变异确认检出并还原
- [x] 发布验收入口、全量构建和相关回归
- [x] 同步文档、自审、归档与提交
