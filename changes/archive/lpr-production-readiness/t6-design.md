# T6 多节点插件状态一致性

## 范围

- 仓库 port 放 `lpr-spi`，返回 descriptor + artifact；Core 负责创建 PluginRuntime，不依赖文件实现。
- 新增独立 `lpr-repository-filesystem` adapter，提供本地 FS 与共享 FS（可挂载 NFS）两种实现。
- 共享根保存发布版本和目标 selection；每个节点配置独立本地缓存，以内容摘要区分 jar、descriptor 和私有库。运行时只加载本地缓存，源端删除不能破坏其他节点的 ClassLoader。
- 版本发布经临时目录 + 原子 rename，禁止覆盖。共享 selection 含 revision、active、previous、enabled；更新在跨进程文件锁内校验 revision，再原子替换。损坏的 marker、无原子替换能力及锁超时都失败，不降级为默认目标。
- 删除最后一个共享版本保留 selection、revision 及版本内容摘要，避免重新安装后的 ABA 及同版本不同内容；节点缓存不自动清除正在使用的版本。

## 协调

- 节点先启动候选，再以读取到的 revision 提交目标状态；冲突则清理候选，保留原路由。
- 周期协调只应用共享目标，不回写目标；启动前后检查 revision，迟到结果不能覆盖新指令。未完成的 STOPPING 版本继续受 T11 保护。
- operator start/stop/activate/rollback 更新目标；宿主启动/关闭及读取远端目标不发布新的操作。
- 自动回滚只有在本地仍应用同一 revision 时才可提交；旧观察任务不能撤销更新的集群命令。
- 共享模式的 broadcast INSTALL 对完全相同的 artifact/descriptor 幂等，内容不同拒绝；目标状态通过周期协调保证遗漏消息的节点收敛。

## 查询与部署

- `PluginsInfoApi` 增加集群查询，REST 增加 `GET /1.0/kb/pluginsInfo/cluster`；返回共享目标 revision/version/enabled 以及每个节点的已报告版本、状态、更新时间、过期与收敛标志。
- 已有节点信息源保持复用；周期协调后刷新本节点报告。查询不把缺报、过期或仅版本匹配视为强一致。
- 配置走 PluginRuntimeConfig：模式 local/shared、共享根、协调间隔、锁超时、节点报告过期阈值；install.dir 为节点本地根，shared 模式不得与共享根重叠。
- NFS 部署必须提供可靠跨节点 advisory locks 与同目录原子 rename。自动化验证使用独立进程和同一共享目录，不宣称已验收真实 NFS mount。

## 装配与兼容

- 原 `providePluginRepository` 改为按配置返回 SPI 实现；`providePluginInstaller`、manager provider 的消费关系保持，新增协调服务 singleton。
- 全仓迁移旧 PluginRepository 构造与导入；原 ACTIVE Properties 读取兼容 revision 缺省为 0、enabled 缺省为 true。
- T11 93 项目构建和 198 项测试作为基线；T7–T10 保留待办。
