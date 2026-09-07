# 架构图

两套图，都是 SVG，可直接在浏览器或 IDE 里打开，也能贴进 PPT 和 Confluence。

- **业务架构**回答「这个业务由哪些概念构成、边界画在哪、哪些值得长期投入」
- **4+1 视图**回答「代码怎么组织、运行时怎么跑、怎么部署」

两套图共用一套栅格与配色，改图时**复制现有文件换文本、按步长增删格子**，不要从零手写坐标。

---

## 一、业务架构 [`business/`](business/)

按推导顺序排列，**顺序本身是方法的一部分**：先有目标与价值，才知道流程该优化什么；先有对象与规则，才知道系统要保护哪些事实。

| 图 | 回答什么 | 最值得看的一处 |
|---|---|---|
| [business-architecture.svg](business/business-architecture.svg) | 全局分层：场景层 / 产品功能层 / 领域模型层 / 依赖层 + 运营与数据两翼 | 依赖层没有一条硬编码集成——外部系统一律挂在扩展点上 |
| [value-stream.svg](business/value-stream.svg) | 从「订阅承诺」到「已收回的收入」的五个阶段，每阶段的结果、责任交接与异常退出 | 下半条泳道：每个阶段都可能离开主线，收入闭环的标准是**不存在悬空发票** |
| [capability-map.svg](business/capability-map.svg) | 价值流主轴 × 五个一级能力域 × 贯穿能力 | 「插件扩展与运行时装载」是本 fork 相对上游唯一被重建的贯穿能力 |
| [object-lifecycles.svg](business/object-lifecycles.svg) | 订阅 / 发票 / 支付 / 重试计划 / 逾期状态五条生命周期如何通过事件协作 | 没有任何一个对象直接驱动另一个对象的状态 |
| [extension-points.svg](business/extension-points.svg) | 13 个扩展点分别留在价值流的哪一段，现有实现是谁 | `EntitlementPluginApi` 是唯一能**否决**业务操作的扩展点 |
| [exception-decision-matrix.svg](business/exception-decision-matrix.svg) | 资金结果确定性 × 剩余应收 的处置决策 | 支付超时只触发回查与升级，绝不自动改成失败——那会让重试变成重复扣款 |
| [metric-tree.svg](business/metric-tree.svg) | 目标 / 过程 / 护栏三层指标与治理闭环 | 护栏指标里有一项是「插件故障导致的降级」 |

## 二、4+1 架构视图 [`4plus1/`](4plus1/)

五个视图不是五份独立文档，是同一个系统的五个投影。**只画其中一两个不叫做了 4+1**——它的价值恰恰在于视图之间必须互相对齐。

| 视图 | 给谁看 | 图 |
|---|---|---|
| 场景（+1） | 所有人 | [1-scenario-view.svg](4plus1/1-scenario-view.svg) —— 18 个用例 + 5 条 NFR，每个用例标出驱动源 |
| 逻辑 | 设计者 | [2-logical-view.svg](4plus1/2-logical-view.svg) —— LPR 与桥接层的类结构，右侧是四条不变量及其违规判据 |
| 开发 | 开发者 | [3-development-view.svg](4plus1/3-development-view.svg) —— 88 模块六层，两条红色禁止边 |
| 进程 | 集成者 | [4a-process-view-billing.svg](4plus1/4a-process-view-billing.svg) —— 出账 → 扣款 → 重试，标注锁范围与超时预算<br>[4b-process-view-hot-upgrade.svg](4plus1/4b-process-view-hot-upgrade.svg) —— 插件热升级与回滚四阶段 |
| 物理 | 运维者 | [5-physical-view.svg](4plus1/5-physical-view.svg) —— 四层部署 + 横向外部依赖，标注单点与副本数 |
| 整合 | 架构评审 | [6-integration-view.svg](4plus1/6-integration-view.svg) —— 六处冲突全部横跨两个视图，附 NFR 覆盖矩阵 |

### 五条非功能需求落在哪

| NFR | 主要承担的视图 | 对应的架构约束 |
|---|---|---|
| 升级无空窗 | 逻辑 · 进程 | [A5](../../CLAUDE.md) 服务注册按 `(pluginId, version)` |
| 插件隔离与可卸载 | 逻辑 · 开发 | A1 / A3 / A4 |
| 插件故障不拖垮核心 | 进程 | 调用点「记 warn 然后跳过」 |
| 集群一致 | 物理 | 节点命令广播 + 每节点各自落盘 |
| 核心不认识具体插件 | 开发 | A6 core 不得依赖任何具体插件 |

---

## 三、改图

图是**手写的结构化 SVG**，不是从 PlantUML / Mermaid 生成的——节点连线图的自动布局横竖不对齐、连线交叉，正是这批图要避免的。

改图方法：

1. 复制一份最接近的现有图，**只改 `<text>` 内容和 `<rect>` 坐标，不动 `<style>` 块**（那是风格一致性的来源）
2. 增删格子按网格步长推，不要手算出 287、578 这类残留坐标
3. 中文按每字约 12px（`.cell` 13px）估宽，确认文本不超出所在 `<rect>`
4. 改完校验并肉眼过一遍：

```bash
python3 -c "import xml.dom.minidom,sys; xml.dom.minidom.parse(sys.argv[1])" <file>.svg
rsvg-convert -w 1400 <file>.svg -o /tmp/check.png     # brew install librsvg
```

共用约定：画布宽 1200 或 1400；内容区左边距 24 或 68；字体栈 `'Helvetica Neue', Helvetica, Arial, 'PingFang SC', 'Microsoft YaHei', sans-serif`；每图自带 `<defs>` 里的箭头 marker。

方法论来自 `~/research/repo-distill` 的 `business-architecture` 与 `four-plus-one-architecture` 两个 skill。
