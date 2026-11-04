# changes/ — 变更过程

> 按 [SDD Kit](../spec/constitution.md) 组织的规范驱动开发变更目录。
> 这里只放**进行中和已归档的变更**；系统当前真相见 [`../spec/`](../spec/)。

## 目录结构

```
changes/
├── <name>/                 # 进行中的变更
│   ├── proposal.md         # Why / What Changes / Impact
│   ├── tasks.md            # 可勾选任务清单
│   └── specs/              # 可选：本变更涉及的 capability delta
│       └── <capability>.md
└── archive/<name>/         # 已完成归档
    └── ARCHIVED.md
```

## 当前变更

无。

## 已归档变更

| 变更 | 主题 | 归档日期 |
|---|---|---|
| [archive/lpr-production-readiness](archive/lpr-production-readiness/ARCHIVED.md) | T1–T11：可观测性、故障隔离、运维一致性及进程外研究 PoC | 2026-11-04 |
| [archive/osgi-to-lpr](archive/osgi-to-lpr/ARCHIVED.md) | 用 LPR 替换 OSGi/Felix，迁移插件与扩展点 | 2026-10-21 |

## 开新变更的步骤

1. 复制 `templates/change-proposal.md` 与 `templates/change-tasks.md` 到 `changes/<name>/`。
2. 写 `proposal.md`：Why / What Changes / Impact。
3. 写 `tasks.md`：拆成可勾选小步，最后一步是 verify + 合并回 spec + 归档。
4. 如需改 capability，在 `changes/<name>/specs/<cap>.md` 写 delta（## ADDED/MODIFIED/REMOVED Requirements）。
5. 实现 + 测试，全量构建通过。
6. 把 delta 合并回 `spec/` 对应 capability，更新「实现与验证对照」。
7. 写 `ARCHIVED.md`，把目录移到 `changes/archive/<name>/`。
