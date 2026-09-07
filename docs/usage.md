# 使用指南

从零把这个 fork 跑起来、装上插件、日常运维。

> **和上游 Kill Bill 的关系**：业务语义（订阅、发票、支付重试、目录、账户）**完全没改**，
> 官方文档 <https://docs.killbill.io> 直接适用。
>
> 改掉的只有**插件运行时**——OSGi 换成了自研的 LPR。凡是官方文档里出现
> bundle / OSGi / KPM 的地方，本文替代它。判断方法很简单：
> **讲业务的看官方，讲插件的看这里。**

---

## 一、跑起来

### 前置

| | 要求 |
|---|---|
| JDK | **21**。注意 `export JAVA_HOME` 对 jenv shim 无效，见[构建环境](development/build-environment.md#-坑-1jenv-shim-会覆盖-java_home) |
| Maven | 3.6.3+ |
| 数据库 | MySQL 或 PostgreSQL |

### 构建

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 21)
mvn -B -ff -T 1C clean install -DskipTests
```

88 个模块，约 3 分钟。跑测试见[构建环境](development/build-environment.md)——
本机跑 DB 测试需要一串特定参数，缺任何一段都会产生看起来像回归的失败。

### 建库

```bash
./killbill/bin/db-helper -a create        # 官方脚本，需要 gnu-getopt
```

本机 `gnu-getopt` 常常没装。替代做法见
[构建环境 · 坑 4](development/build-environment.md#解决用-orbstack-dev-infra-的-postgresql)，
那里有按正确顺序拼 DDL 的方法（顺序错了表结构就不对）。

### 起服务器

```bash
./killbill/bin/start-server -s              # 默认 8080，JMX 8989
./killbill/bin/start-server -s -p 9090       # 换端口
./killbill/bin/start-server -s -d            # 开调试端口 12345
```

它跑的是 `mvn jetty:run`，配置取自
`killbill/profiles/killbill/src/main/resources/killbill-server.properties`，
用 `PROPERTIES=/path/to/your.properties` 覆盖。

确认起来了：

```bash
curl -v http://127.0.0.1:8080/1.0/healthcheck
```

---

## 二、插件配置

四个属性，都放 `killbill.properties`：

| 属性 | 默认 | 说明 |
|---|---|---|
| `org.killbill.billing.plugin.install.dir` | — | 插件根目录，每个插件一个子目录 |
| `org.killbill.billing.plugin.mandatory.plugins` | 空 | 逗号分隔的插件 id。**这些启动失败就中断整个启动** |
| `org.killbill.billing.plugin.start.enabled` | `true` | 设 `false` 只发现不启动，用来排查"装了什么" |
| `org.killbill.billing.plugin.parentFirstPackages` | 空 | 额外的 parent-first 包前缀，见下 |

> `parentFirstPackages` 加一个前缀，就意味着**所有插件都不能再自选那个库的版本**。
> 只有当某个类型确实要跨插件边界传递时才加。这是隔离性和共享性之间的交易，不是调优项。

对比 OSGi 时代：原来是十几个属性，其中一半（bundle 缓存目录、存储名、两坨 export 清单）
描述的是 OSGi 框架自身。那些随框架一起消失了。

---

## 三、插件目录长什么样

```
plugins/                       ← install.dir
└── killbill-stripe/           ← 插件 id
    ├── ACTIVE                 ← properties: active=1.3.0, previous=1.2.0
    ├── DISABLED               ← 存在则整个插件不加载
    ├── 1.2.0/
    │   ├── plugin.jar
    │   └── plugin.yaml
    └── 1.3.0/
```

运行时**靠列目录发现插件**。所以往里放东西必须是原子的：先落临时目录，
成功后再移动就位——写了一半的版本目录和真的版本目录长得一模一样。

`plugin.yaml`：

```yaml
id: killbill-stripe          # 身份，不是展示名。决定目录名和服务注册名
name: Stripe Payment Plugin
version: 1.3.0               # SemVer
entrypoint:
  class: org.killbill.billing.plugin.stripe.StripePlugin
config:                      # 可选。优先级高于 killbill.properties
  org.killbill.billing.plugin.stripe.apiKey: "sk_test_..."
```

---

## 四、插件运维

**管理面只有一个**：Kill Bill 既有的节点命令 `/1.0/kb/nodesInfo`。
命令广播到集群每个节点，各自执行。没有第二套 REST——那意味着鉴权、审计、
集群广播都要再实现一遍。

### 安装

```bash
curl -X POST http://127.0.0.1:8080/1.0/kb/nodesInfo \
  -u admin:password \
  -H 'Content-Type: application/json' \
  -H 'X-Killbill-CreatedBy: admin' \
  -d '{
        "nodeCommandType": "INSTALL_PLUGIN",
        "nodeCommandProperties": [
          {"key": "pluginName",    "value": "killbill-stripe"},
          {"key": "pluginVersion", "value": "1.3.0"},
          {"key": "uri",           "value": "file:///tmp/stripe-plugin-1.3.0.jar"},
          {"key": "sha1",          "value": "..."}
        ]
      }'
```

| 属性 | |
|---|---|
| `uri` | **必填**。`file:` 或 `http(s):` |
| `sha1` | 可选。校验不过则整个安装回滚，不留残骸 |
| `descriptor` | 可选。默认用 jar 里自带的 `plugin.yaml` |

**安装不等于启动。** 装完插件是 `INSTALLED`，不提供任何服务。
这是刻意的——"让一个版本可见"和"让它接管流量"是两个决定，分开才有回滚余地。

### 启停

把上面的 `nodeCommandType` 换成：

| 命令 | 效果 |
|---|---|
| `START_PLUGIN` | 启动。带 `pluginVersion` 则先切 ACTIVE 再启动 |
| `STOP_PLUGIN` | 停止，不删文件 |
| `RESTART_PLUGIN` | 停止再启动。带版本即为升级 |
| `UNINSTALL_PLUGIN` | 先停止，再删除该版本目录 |

### 升级与回滚

```
1. INSTALL_PLUGIN  1.3.0     ← 1.2.0 仍在服务
2. START_PLUGIN    1.3.0     ← 切换，无空窗
3. 不对劲？START_PLUGIN 1.2.0 ← 回滚
```

升级期间新旧两个版本**同时加载**，服务注册按 `(pluginId, version)` 作用域撤销——
这是"无空窗"能成立的原因，也是为什么不能按 pluginId 一把撤销。

### 查状态

```bash
curl http://127.0.0.1:8080/1.0/kb/pluginsInfo -u admin:password
```

每个**磁盘上的版本**都会出现，其中恰好一个 `isSelectedForStart: true`。
所以这个接口能直接告诉你可以回滚到哪。

---

## 五、写插件

见 [plugin-development.md](development/plugin-development.md)：描述符字段与校验规则、
`PluginContext` 能拿到什么、**pom 的作用域规则**（最容易配错且错了要到运行时才炸）、
`lpr-testkit` 用法、检查清单。

迁移已有的 OSGi 插件见 [plugin-migration-guide.md](migration/plugin-migration-guide.md)。

最短的例子：

```java
public class MyPlugin extends PluginBase {
    @Override
    protected void startPlugin() throws Exception {
        registerService(PaymentPluginApi.class, new MyPaymentApi(killbillApi, clock));
    }
}
```

参考实现在 `plugins/` 里：`hello-world-plugin`（不用框架，看运行时最小面貌）、
`hello-world-java-plugin`（用框架，一次覆盖 payment / invoice / servlet / healthcheck /
事件 / 配置 / metrics 七个面）。

---

## 六、排障

### 插件没被发现

```bash
curl .../1.0/kb/pluginsInfo        # 它在列表里吗？
```

不在 → 描述符没解析成功。日志里搜 `Discovering plugins in`。
常见原因：`id` 不匹配 `[a-z][a-z0-9._-]*`、`version` 不是 SemVer、
`plugin.yaml` 不在版本目录下而在 jar 里（安装时会自动取出，手工摆放则不会）。

### 插件是 FAILED

日志里搜 `Failed to start plugin`。入口类的问题占多数：
类名写错、没有公开无参构造器、没实现 `Plugin`。

### 运行时 ClassCastException，而两个类名一模一样

**这是 ClassLoader 问题，不要看业务代码。** 某个跨边界传递的第三方类型在插件里被
`compile` 依赖了，于是插件加载了自己的一份。改成 `provided`。
判断方法：*这个类型会出现在我注册的服务的方法签名里吗？*

### 插件停了但内存没降 / 反复热更新后 metaspace 涨

有资源没进 `context.resources()`。JDK 21 上，**已终止的线程对象仍然强引用它的 `Runnable`**，
因而钉住插件的 ClassLoader——插件"卸载"了，类一个都没回收。
executor、连接、订阅、watcher 一律要托管。

### 插件的 servlet 404

注册名必须匹配 `[a-z][a-z0-9._-]*`，否则注册会被拒绝并只记一条 warn。
默认注册名是插件 id；显式指定时容易写错。

---

## 七、要不要把官方文档搬过来

**不搬。** 401M、180 篇，其中 **61 篇提到 OSGi/bundle**——那些描述的正是本 fork 删掉的东西。
搬过来等于引入 61 篇需要逐句甄别的内容，而**错的文档比缺的文档代价更大**。

更实际的分工：

| 内容 | 看哪里 |
|---|---|
| 业务概念、REST API、目录配置、支付重试 | <https://docs.killbill.io>（本 fork 没改） |
| 插件运行时、插件开发、插件运维 | 本文档目录 |

真要本地留一份官方文档，`git clone killbill/killbill-docs` 单独放，
**不要并进这个仓库**——它有自己的 Asciidoctor + Ruby 构建链和发布脚本。

---

## 八、与上游的差异速查

| 上游 | 这里 |
|---|---|
| `packaging=bundle` + bnd 指令 | `packaging=jar` + `plugin.yaml` |
| `Import-Package` 白名单 | `ClassLoaderPolicy` 的包前缀（13 条） |
| `KillbillActivatorBase` | `PluginBase` |
| `OSGIKillbillAPI` | `KillbillApi`（同样的 getter，背后不再是 ServiceTracker） |
| `OSGIConfigPropertiesService` | `PluginConfigProperties`（插件描述符优先于全局属性） |
| KPM 装插件 | `INSTALL_PLUGIN` 节点命令 |
| 入口类靠扫描继承关系推断 | `plugin.yaml` 里声明 |
| `NotificationPluginApi` 走 Observable 回调 | 走服务注册表，和其余 12 个扩展点一致 |
| 插件自带 event dispatcher | 不需要了 |
