# Kill Bill + LPR

Kill Bill 计费平台的一个 fork，目标是**用自研的轻量级插件运行时（LPR）替换 Apache Felix / OSGi**。

同时把官方原本分散在 6 个独立仓库的核心模块合并为单一代码仓库、单一 Maven reactor。

---

## 为什么

Kill Bill 的插件层建立在 OSGi 之上，但实际只用到了 OSGi 的三个能力：ClassLoader 隔离、Service Registry、生命周期管理。为此付出的代价是：

- `Import-Package` 白名单成为插件的 ABI 契约（`killbill-oss-parent/pom.xml` 里硬编码 40+ 包名）
- `OSGIConfig` 里两坨约 200 行的 system-packages 导出清单，占该文件 90%
- 9 个模块必须打成 `packaging=bundle`
- 插件入口靠 bnd 扫描继承关系隐式生成，无法显式声明

**LPR 的定位**：保留那三个真正需要的能力，丢掉 OSGi 规范的其余部分。它不是 OSGi 的替代品，而是从 OSGi 的复杂能力中提取出平台实际需要的最小闭环。

---

## 仓库结构

```
.
├── pom.xml                  # 纯 aggregator：只定义 reactor 顺序，不是任何模块的 parent
├── CLAUDE.md                # 工作规范与架构约束（AI 与人都该先读这个）
├── docs/                    # 设计、分析、迁移方案
├── killbill-oss-parent/     # parent pom + BOM，管理全部依赖版本与构建规约
├── killbill-api/            # 核心 API（org.kill-bill.billing）
├── killbill-plugin-api/     # 插件 SPI（org.kill-bill.billing.plugin），零 OSGi 依赖
├── killbill-commons/        # 基础库（org.kill-bill.commons）：clock/queue/jdbi/locker/...
├── lpr/                     # 自研轻量级插件运行时（替换 OSGi），与 Kill Bill 无关
│                            #   lpr-api               插件契约：Plugin / PluginContext / ...
│                            #   lpr-spi               port：ClassLoader 工厂、描述符解析
│                            #   lpr-core              注册表、生命周期、仓库、事件总线
│                            #   lpr-classloader-default  默认隔离后端
│                            #   lpr-descriptor-yaml   plugin.yaml 解析（唯一见 SnakeYAML 的模块）
│                            #   lpr-management        安装/卸载（替代 KPM）
│                            #   lpr-testkit           隔离性与泄漏性测试工具
├── killbill-platform/       # 平台层：lifecycle、server 装配、插件运行时
│                            #   plugin-api      业务模块查插件用的类型化注册表
│                            #   plugin-runtime  把 LPR 接进 Kill Bill 的适配层
├── killbill-plugin-framework/ # 插件侧 SDK（替代 killbill-plugin-framework-java）
│                            #   org.killbill.billing.plugin.runtime  PluginBase / KillbillApi
├── plugins/                 # 迁移过来的插件（16 个在 reactor 内）
│                            #   平台功能：metrics / prometheus / graphite / influxdb / eureka
│                            #   支付：stripe / adyen / gocardless
│                            #   税务：avatax / vertex
│                            #   其他：analytics / email-notifications / custom-email-invoice-formatter
│                            #        currency / deposit / payment-test / catalog-test
│                            #   参考实现：hello-world（无框架）/ hello-world-java（用框架）
└── killbill/                # 业务核心：account/catalog/subscription/entitlement/
                             #          invoice/payment/usage/overdue/junction/jaxrs/...
```

模块的 `<modules>` 顺序就是依赖拓扑，**不要重排**：

```
killbill-oss-parent → killbill-api → killbill-plugin-api → killbill-commons
                   → lpr → killbill-platform → killbill
                   → killbill-plugin-framework → plugins
```

---

## 快速开始

### 前置要求

- **JDK 21**（`project.build.targetJdk`）
- Maven 3.6.3+

> ⚠️ 本机 `mvn` 是 jenv shim，会覆盖你 export 的 `JAVA_HOME`。详见 [docs/development/build-environment.md](docs/development/build-environment.md)。

### 构建

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 21)
/Users/wayne/maven/apache-maven-3.6.3/bin/mvn -B -ff -T 1C clean install \
  -DskipTests=true \
  -Dcheck.skip-spotbugs=true -Dcheck.skip-dependency=true \
  -Dcheck.skip-dependency-scope=true -Dcheck.skip-dependency-versions=true \
  -Dcheck.skip-duplicate-finder=true -Dcheck.skip-enforcer=true -Dcheck.skip-rat=true
```

参考耗时：73 个模块约 3 分钟（`-T 1C`，依赖已缓存）。

### 测试

测试按 TestNG group 划分，由 Maven profile 控制：

```bash
mvn test                        # fast
mvn test -Ptravis               # fast,slow
mvn test -Ppostgresql           # fast,slow,postgresql
mvn test -Pintegration-mysql    # fast,slow,mysql,integration
```

### 本地基础设施

OrbStack 的 `dev-infra` 分组提供：

| 服务 | 地址 | 凭据 |
|---|---|---|
| PostgreSQL 16 | `localhost:5432` | `dev` / `dev` |
| Redis 7 | `localhost:6379` | — |

---

## 当前进度

| Phase | 内容 | 状态 |
|---|---|---|
| 0 | 单仓库聚合 | ✅ 完成 |
| 1 | LPR 骨架（api / spi / core） | ✅ 完成 |
| 2 | ClassLoader PoC（**硬门禁**） | ✅ 通过 |
| 3 | Registry / Lifecycle / Resource / EventBus | ✅ 完成 |
| 4 | 插件仓库 / 描述符 / PluginManager / TestKit | ✅ 完成 |
| 5 | 接入 Kill Bill 核心 | ✅ 完成（1128 项测试全绿） |
| 6 | 平台侧实现替换 | ✅ 完成 |
| 7 | 删除 OSGi + 迁移存量插件 | ✅ **OSGi 已彻底删除**（141 文件 / 16408 行），6 个插件已迁移 |

**扩展点已全部迁移**：13 个 `PluginServiceRegistry` 类型 + 平台服务发布 + 事件分发
+ 节点命令 + HTTP 路由。`NotificationPluginApi` 顺带从"观察者回调"统一成了 registry，
消除了 OSGi 时代唯一的架构不一致。

**已迁移**：插件框架（286 类，1312 项测试）+ **16 个插件**（117 项测试）。
`logger` bundle 不迁移——它的主体是 OSGi `LogService` 桥接，随 OSGi 作废是正确结果。

**留在目录里但不进 reactor 的 3 个**（原因各不相同，都与 OSGi 无关）：

| 插件 | 原因 |
|---|---|
| `payment-retries` | oss-parent 0.140.25；`PluginTenantContext(UUID)` 等构造器签名已变 |
| `coupon-plugin-demo` | `EntitlementSpecifier.getQuantity()` 未实现（API 漂移） |
| `qualpay` | 依赖的 `qualpay-java-client:1.1.9` 在任何可达仓库里都取不到 |

**未迁移的 11 个**（oss-parent 0.5–0.143，比当前落后 4 个大版本以上）：
accertify、bitcoin、bridge、dwolla、feedzai、forte、meter、moneris、payeezy、recurly、zuora。

完整方案见 [docs/migration/osgi-to-lpr-plan.md](docs/migration/osgi-to-lpr-plan.md)。

---

## 文档

| 入口 | 用途 |
|---|---|
| [CLAUDE.md](CLAUDE.md) | **架构约束与工作规范**。改代码前必读 |
| [docs/README.md](docs/README.md) | 文档索引 |
| [docs/design/lightweight-plugin-runtime.md](docs/design/lightweight-plugin-runtime.md) | **LPR 设计**：模型、自研 vs 复用的边界、以及设计与实现的出入 |
| [docs/analysis/](docs/analysis/) | 改造前的代码摸底（带文件路径与行号） |
| [docs/migration/](docs/migration/) | 实施方案与进度 |
| [docs/development/plugin-development.md](docs/development/plugin-development.md) | **写一个新插件**：描述符字段、平台能力、testkit、热更新与回滚 |
| [docs/migration/plugin-migration-guide.md](docs/migration/plugin-migration-guide.md) | 从 OSGi bundle 迁移已有插件 |
| [docs/development/build-environment.md](docs/development/build-environment.md) | 构建与测试环境，含 7 个已知坑 |
| [docs/development/issue-log.md](docs/development/issue-log.md) | 问题账本：踩过的坑与解决方式 |

---

## 与上游的关系

这是一个 **fork，不是镜像**。插件运行时被整体替换、`OSGIServiceRegistration` 等接口被重命名之后，与 upstream 的 merge 能力已经不复存在。

保留官方目录结构（`killbill/`、`killbill-platform/` 等原样嵌套）的目的是降低一次性迁移风险、保留代码归属可读性，以及让 `${project.parent.basedir}` 这类路径引用继续有效——不是为了长期同步。

上游：https://github.com/killbill/killbill

---

## License

Apache License 2.0（沿用上游）
