# 分析报告 01：6 个核心仓库的 Maven 结构与依赖图

> 调研对象：`killbill-oss-parent` / `killbill-api` / `killbill-plugin-api` / `killbill-commons` / `killbill-platform` / `killbill`
> 目的：为"合并成单仓库多模块顶级工程"提供依据
> 状态：Phase 0 已依据本报告完成改造

---

> ⚠️ **部分内容是改造前的快照。** 依赖图、版本属性、构建插件那几节仍然准确；
> 但模块清单里的 `osgi` / `osgi-api` / `osgi-bundles` **已被删除**，
> 且新增了 `lpr/`、`killbill-platform/plugin-api`、`plugin-runtime`、`plugins/`。
> 现在的模块结构见 [`../../README.md`](../../README.md)。



## 一、6 个仓库根 pom 的坐标与 parent 声明

| 仓库 | groupId | artifactId | version | packaging | parent（改造前） |
|---|---|---|---|---|---|
| killbill-oss-parent | `org.kill-bill.billing`（显式） | `killbill-oss-parent` | **0.147.21-SNAPSHOT** | pom | **无 parent**（根） |
| killbill-api | 继承 `org.kill-bill.billing` | `killbill-api` | **0.55.2-SNAPSHOT** | jar | oss-parent 0.147.2 |
| killbill-plugin-api | `org.kill-bill.billing.plugin`（覆盖） | `killbill-plugin-api` | **0.28.2-SNAPSHOT** | pom | oss-parent 0.147.2 |
| killbill-commons | `org.kill-bill.commons`（覆盖） | `killbill-commons` | **0.27.3-SNAPSHOT** | pom | oss-parent 0.147.14 |
| killbill-platform | 继承 `org.kill-bill.billing` | `killbill-platform` | **0.42.3-SNAPSHOT** | pom | oss-parent 0.147.15 |
| killbill | 继承 `org.kill-bill.billing` | `killbill` | **0.25.5-SNAPSHOT** | pom | oss-parent 0.147.20 |

### 合并的第一个坑：`<relativePath>` 缺失

5 个根 pom **都没写 `<relativePath>`**，Maven 默认查 `../pom.xml`。一旦在父目录放聚合 pom，Maven 会把它当成 `../pom.xml` 去匹配 `killbill-oss-parent:0.147.x`，GAV 对不上直接构建失败。

**必须显式改成** `<relativePath>../killbill-oss-parent/pom.xml</relativePath>`。

### 第二个坑：4 个不同的 parent 版本

0.147.2 / 0.147.14 / 0.147.15 / 0.147.20，而 oss-parent 自身已是 0.147.21-SNAPSHOT。合并后必须统一。

---

## 二、模块清单（全部 73 个 pom）

### killbill-oss-parent
无 `<modules>`，纯 parent pom（2647 行）。附带 `bin/retry` 脚本（CI 重试用）。

### killbill-api
无 `<modules>`，单 jar 模块。依赖仅 3 个：`jackson-annotations`、`jakarta.xml.bind-api`、`joda-time`。

### killbill-plugin-api（groupId `org.kill-bill.billing.plugin`，8 个子模块）

`notification, payment, currency, control, invoice, catalog, entitlement, usage`
→ artifactId 为 `killbill-plugin-api-{名}`，全部 jar。

### killbill-commons（groupId `org.kill-bill.commons`，含 1 层嵌套）

| 目录 | artifactId | packaging |
|---|---|---|
| clock | killbill-clock | jar |
| concurrent | killbill-concurrent | jar |
| config-magic | killbill-config-magic | jar |
| **embeddeddb** | **killbill-embeddeddb** | **pom**（含 4 子模块） |
| embeddeddb/common | killbill-embeddeddb-common | jar |
| embeddeddb/h2 | killbill-embeddeddb-h2 | jar |
| embeddeddb/mysql | killbill-embeddeddb-mysql | jar |
| embeddeddb/postgresql | killbill-embeddeddb-postgresql | jar |
| jdbi | killbill-jdbi | jar |
| locker | killbill-locker | jar |
| queue | killbill-queue | jar |
| skeleton | killbill-skeleton | jar |
| xmlloader | killbill-xmlloader | jar |
| automaton | killbill-automaton | jar |
| jooby | killbill-jooby | jar |
| metrics | killbill-metrics | jar |
| metrics-api | killbill-metrics-api | jar |
| utils | killbill-utils | jar |

### killbill-platform（8 个一级模块，osgi-bundles 下 3 层嵌套）

| 路径 | artifactId | packaging |
|---|---|---|
| platform-api | killbill-platform-api | jar |
| osgi-api | killbill-platform-osgi-api | jar |
| base | killbill-platform-base | jar |
| lifecycle | killbill-platform-lifecycle | jar |
| osgi | killbill-platform-osgi | jar |
| **osgi-bundles** | **killbill-platform-osgi-all-bundles**（目录名 ≠ artifactId） | pom |
| osgi-bundles/libs | killbill-platform-osgi-lib-bundles | pom |
| osgi-bundles/libs/killbill | killbill-platform-osgi-bundles-lib-killbill | jar |
| osgi-bundles/libs/slf4j-osgi | killbill-platform-osgi-bundles-lib-slf4j-osgi | jar |
| osgi-bundles/bundles | killbill-platform-osgi-bundles | pom |
| osgi-bundles/bundles/logger | killbill-platform-osgi-bundles-logger | **bundle** |
| osgi-bundles/bundles/kpm | killbill-platform-osgi-bundles-kpm | **bundle** |
| osgi-bundles/bundles/eureka | killbill-platform-osgi-bundles-eureka | **bundle** |
| osgi-bundles/bundles/graphite | killbill-platform-osgi-bundles-graphite | **bundle** |
| osgi-bundles/bundles/influxdb | killbill-platform-osgi-bundles-influxdb | **bundle** |
| osgi-bundles/bundles/prometheus | killbill-platform-osgi-bundles-prometheus | **bundle** |
| osgi-bundles/bundles/metrics | killbill-platform-osgi-bundles-metrics | **bundle** |
| osgi-bundles/tests/beatrix | killbill-platform-osgi-bundles-test-beatrix | **bundle** |
| osgi-bundles/tests/payment | killbill-platform-osgi-bundles-test-payment | **bundle** |
| osgi-bundles/defaultbundles | killbill-platform-osgi-bundles-defaultbundles | pom（assembly） |
| platform-test | killbill-platform-test | jar |
| server | killbill-platform-server | **war** |

`defaultbundles` 不是聚合器，它依赖 kpm/logger/metrics 三个 bundle，用 `maven-assembly-plugin:single` 打成默认 bundle 包，并把 `maven-jar-plugin` 的 default-jar 绑到 `<phase>none</phase>` 禁掉。

> **根 pom 的 `<modules>` 顺序有强制注释**：`osgi-bundles` 必须排在 `platform-test` 之前。

### killbill（16 个一级模块 + profiles 下 2 个）

`account, api, beatrix, catalog, subscription, entitlement, invoice, junction, overdue, payment, usage, util, jaxrs, tenant, currency, profiles`

artifactId 除两个特例外均为 `killbill-<目录名>`：
- `api` → **`killbill-internal-api`**（注意：不是 `killbill-api`，别和 killbill-api 仓库混淆）
- `profiles` → `killbill-profiles`（pom），下辖 `killbill-profiles-killbill`（war）和 `killbill-profiles-killpay`（war）

---

## 三、killbill-oss-parent 的作用

2647 行的巨型 BOM + 构建规约 pom，管理全部第三方库版本、全部插件版本、OSGi bnd 指令、测试 profile、发布配置。

### 3.1 关键版本属性（第 140–146 行）

```xml
<killbill-api.version>0.55.1</killbill-api.version>
<killbill-base-plugin.version>5.2.1</killbill-base-plugin.version>
<killbill-client.version>1.5.3</killbill-client.version>
<killbill-commons.version>0.27.2</killbill-commons.version>
<killbill-platform.version>0.42.2</killbill-platform.version>
<killbill-plugin-api.version>0.28.1</killbill-plugin-api.version>
<killbill-testing-mysql.version>1.0.2</killbill-testing-mysql.version>
```

注意属性名用**中划线** `killbill-api.version`，不是 `killbill.api.version`。

### 3.2 反向引用与 `${killbill.version}` 的陷阱

oss-parent 的 `dependencyManagement` 里管理了约 **100 条** `org.kill-bill.*` 条目，覆盖 6 个仓库的每一个 artifact（含 test-jar 和 `classes` classifier 变体）。

**致命细节**：`${killbill.version}` 这个属性**在 oss-parent 里从未定义，只被使用**。它由下游 `killbill/pom.xml:64` 提供：

```xml
<killbill.version>${project.version}</killbill.version>
```

oss-parent → killbill 核心模块的循环，靠"属性延迟解析 + 子项目自己定义属性"来打破。**但这意味着除 killbill 之外的任何模块解析这段 depMgmt 时，都会拿到字面量 `${killbill.version}`。**

更麻烦的是，6 个仓库**恰好全部比 oss-parent 锁定的版本高一个 patch**：

| 属性 | oss-parent 锁定 | 本地实际 |
|---|---|---|
| killbill-api.version | 0.55.1 | 0.55.2-SNAPSHOT |
| killbill-plugin-api.version | 0.28.1 | 0.28.2-SNAPSHOT |
| killbill-commons.version | 0.27.2 | 0.27.3-SNAPSHOT |
| killbill-platform.version | 0.42.2 | 0.42.3-SNAPSHOT |
| killbill.version（未定义） | — | 0.25.5-SNAPSHOT |

不改会**静默从 Maven Central 拉旧 jar**，编译的是本地源码，链接的却是上一个 release —— 这是最难排查的一类问题。

### 3.3 OSGi 相关依赖管理

```
org.apache.felix : org.apache.felix.framework  : 7.0.5
org.apache.felix : org.apache.felix.log        : 1.2.6
org.osgi         : osgi.core                   : 8.0.0
org.osgi         : org.osgi.core               : 6.0.0    ← 两个 core 并存
org.osgi         : org.osgi.service.event      : 1.4.1
org.osgi         : org.osgi.service.http       : 1.2.2
org.osgi         : org.osgi.service.log        : 1.5.0
org.osgi         : osgi.annotation             : 8.1.0
```

**没有** compendium（用的是拆分后的 `org.osgi.service.*` 独立 artifact）。

### 3.4 OSGi bnd 指令属性（第 150–223 行）

```xml
<osgi.activator>$${classes;EXTENDS;org.killbill.billing.osgi.libs.killbill.KillbillActivatorBase}</osgi.activator>
<osgi.dependency>*;scope=compile|runtime;inline=true</osgi.dependency>
<osgi.symbolic-name>${project.groupId}.${project.artifactId}</osgi.symbolic-name>
<osgi.transitive-dependency>true</osgi.transitive-dependency>
<osgi.noclassforname>true</osgi.noclassforname>
<osgi.import>…约 50 行 Import-Package 白名单…</osgi.import>
```

`osgi.import` 硬编码了 40 多个 `org.killbill.billing.*;version=0.0.0` 包名，外加 `org.apache.shiro;version=1.3`、`org.joda.time;version=2.9`、`org.slf4j;version=1.7.2`、`org.osgi.service.{deploymentadmin,event,http,log}`。

> **这份清单就是插件的 ABI 契约。** 迁移到 LPR 后整块删除。

---

## 四、Java 版本 / 构建插件

### Java 版本

```xml
<project.build.targetJdk>21</project.build.targetJdk>
<project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
<build.jvmsize>1024m</build.jvmsize>
```

`maven-compiler-plugin` 3.11.0 同时设置 source/target/release，并加 `<fork>true</fork>`、`<parameters>true</parameters>`、`-XDignore.symbol.file`。

两个 JDK 激活 profile 现在都是空壳/仅调 surefire 参数：
- `javac-release`：`<jdk>[11,17)</jdk>`，profile 体为空
- `javac17-release`：`<jdk>[17,)</jdk>`，只覆盖 `surefireArgLine` 加一堆 `--add-opens`

### 测试

`maven-surefire-plugin` 3.2.5，`<runOrder>random</runOrder>`，`<trimStackTrace>false</trimStackTrace>`，`user.timezone=GMT`。
默认 `<surefireArgLine>-Xmx1024m --illegal-access=permit</surefireArgLine>`；killbill-commons 覆盖了它，去掉 `--illegal-access=permit` 并加了 3 个 `--add-opens`。

### 需要注意的构建插件行为

- **`sortpom` 绑在 `verify` 阶段执行 `sort` goal，会自动重写 pom.xml**（`nrOfIndentSpace=4`、`spaceBeforeCloseEmptyElement=true`、`sortDependencies=groupId,artifactId,scope`）。手改 pom 后跑一次 verify 再提交，避免 diff 噪音。
- 一整套 `check.*` 开关属性控制各项检查：`check.skip-{dependency,dependency-scope,dependency-versions,duplicate-finder,enforcer,rat,spotbugs}`。CI 里大量用 `-Dcheck.skip-xxx=true` 关掉。
- `maven-bundle-plugin` 5.1.9（`<extensions>true</extensions>`），覆盖 bnd 为 `biz.aQute.bnd:biz.aQute.bndlib:6.3.1`（为了关掉 Class.forName 扫描）。
- **显式使用 maven-bundle-plugin 的 9 个模块全部在 killbill-platform**；killbill、commons、api、plugin-api 没有任何 bundle 模块。
- **maven-shade-plugin 使用者**：`commons/jooby`、`commons/queue`、`killbill/catalog`、`killbill/overdue`。

---

## 五、6 个仓库的依赖方向

```
                    killbill-api  (org.kill-bill.billing)
                       ▲   ▲   ▲   ▲
        ┌──────────────┘   │   └────────┐          │
        │                  │            │          │
killbill-plugin-api   killbill-commons  │          │
 (org.kb.billing.plugin) (org.kb.commons)          │
        ▲   ▲                ▲  ▲       │          │
        │   └────────┬───────┘  │       │          │
        │            │          │       │          │
        │      killbill-platform ───────┘          │
        │            ▲                             │
        └────────── killbill ──────────────────────┘

killbill-oss-parent
   ├─ 是上面全部 5 个仓库的 <parent>
   └─ 在 dependencyManagement 里【反向】引用全部 5 个仓库的 artifact
```

**拓扑序（即聚合 pom 的 `<modules>` 顺序）**：
`killbill-oss-parent → killbill-api → killbill-plugin-api → killbill-commons → killbill-platform → killbill`

逐条明细：

| 源 → 目标 | 引用的 artifact |
|---|---|
| killbill-plugin-api → killbill-api | `killbill-api` |
| killbill-commons → killbill-api | **仅 1 处**：`commons/queue/pom.xml:98`（注释："Just need killbill-api for the QueueRetryException"） |
| killbill-platform → killbill-api | `killbill-api` |
| killbill-platform → killbill-plugin-api | 全部 8 个 |
| killbill-platform → killbill-commons | 15 个 |
| killbill → killbill-api | `killbill-api` |
| killbill → killbill-plugin-api | 全部 8 个 |
| killbill → killbill-commons | 15 个 |
| killbill → killbill-platform | platform-api, base, lifecycle, osgi, osgi-api, osgi-bundles-lib-killbill, server, test（8 个） |

**无环**（除 oss-parent 那条 depMgmt 反向边）。

### 仓库外的 org.kill-bill.* 依赖（仍需从 Central 拉）

- `org.kill-bill.billing:killbill-client-java` (1.5.3)
- `org.kill-bill.billing.plugin.java:killbill-base-plugin` (5.2.1)
- `org.kill-bill.testing:testing-mysql-server` (1.0.2)

---

## 六、Profiles / CI

### DB 测试矩阵（都只改 surefire 的 `<groups>`）

| profile | TestNG groups |
|---|---|
| `default`（activeByDefault） | `fast` |
| `h2` | `fast,slow` |
| `mysql` | `fast,slow,mysql` |
| `postgresql` | `fast,slow,postgresql` |
| `integration-h2` / `integration-mysql` / `integration-postgresql` | 加 `integration` |
| `travis` | `fast,slow`（名字是历史遗留，GH Actions 仍在用） |
| `travis-nodb` | `fast` |
| `test-stress` | `stress` |

发布 profile：`sonatype-oss-release`（gpg 签名 + javadoc + sources + central-publishing-maven-plugin）。

### 官方 CI 的"多仓库联编舞蹈"（合并后可整个删除）

`killbill-oss-parent/.github/workflows/ci.yml` 的逻辑：

1. checkout 8 个仓库到同级目录（oss-parent, api, plugin-api, commons, plugin-framework-java, platform, client-java, killbill）
2. 先 `mvn clean install` oss-parent，用 `help:evaluate` 取出 pomVersion
3. 对每个子仓库循环：`mvn versions:update-parent -DallowSnapshots -DparentVersion="[$pomVersion]"` → build → 回到 oss-parent 执行 `mvn versions:set-property -Dproperty=<killbill-xxx.version> -DnewVersion=$version` → 再 build oss-parent
4. 最后 build killbill
5. 按 `matrix.suite`（h2/mysql/postgresql）逐仓库跑测试，flaky 的用 `bin/retry` 包一层

全程 `MAVEN_FLAGS: "-B -ff -Dcheck.skip-spotbugs=true -DskipTests=true"`，JDK 21 temurin。

> **这套脚本就是"合并成单仓库"最有力的论据 —— 官方自己在用 CI 模拟单仓库。**

`killbill` 仓库另有 e2e job：起 MySQL 8.0 → `./bin/db-helper -a create` → curl 拉三个插件的 ddl.sql → `./bin/start-server -s` → 轮询 `/1.0/healthcheck` → POST 建 tenant → 跑 `killbill-integration-tests`（Ruby 2.7）。

`killbill/.circleci/config.yml` 是废弃遗留物（MAVEN_OPTS 里还是 JDK 21 下已失效的 CMS GC 参数）。

---

## 七、合并风险清单（Phase 0 checklist）

1. ✅ **`<relativePath>` 缺失**（5 个根 pom）—— 顶级聚合 pom 一放就炸。**第一优先级**
2. ✅ **`${killbill.version}` 未定义** —— 需在 oss-parent 上提定义
3. ✅ **5 个版本属性 vs 本地实际版本全部差一个 patch** —— 不改会静默拉旧 jar
4. ✅ **4 个不同的 parent 版本**需统一到 0.147.21-SNAPSHOT
5. ⚠️ **3 个 groupId 并存**（billing / billing.plugin / commons），**不能强行统一**
6. ⚠️ **`main.basedir` 属性**：killbill 的 16 个模块用 `${project.parent.basedir}`、profiles 下 2 个用 `${project.parent.parent.basedir}` 指向 `spotbugs-exclude.xml`。**保留原目录层级即不受影响**（这是"聚合式"方案的实际收益）
7. ⚠️ **目录名 ≠ artifactId 两处**：`osgi-bundles` → `killbill-platform-osgi-all-bundles`；`killbill/api` → `killbill-internal-api`
8. ⚠️ **模块顺序硬性要求**：killbill-platform 的 `osgi-bundles` 必须在 `platform-test` 之前
9. ⚠️ **sortpom 绑在 verify 阶段**会自动重写手改的 pom
10. ⚠️ **`deploy.deploy-at-end`** 在 platform 被改成 false，合并成单 reactor 后语义会变
11. ⚠️ **`osgi.import` 白名单**是插件 ABI 契约（本次改造中整块删除）
12. ℹ️ 6 个仓库都没有 `.git`（纯拷贝），也没有 mvnw

---

## 八、构建环境

- Java 目标版本 **21**；本地有 Zulu 21.0.11，但 jenv 默认是 8 → 需 `jenv shell 21` 或 `export JAVA_HOME=$(/usr/libexec/java_home -v 21)`
- Maven 3.6.3，localRepository = `/Users/wayne/maven/repository`（10G）
- `~/.m2/settings.xml` 配了 `<mirrorOf>*</mirrorOf>` → aliyun。**这会屏蔽 sonatype snapshots**，若某依赖只在 snapshot 仓库需改成 `*,!sonatype-nexus-snapshots`
- 本地仓库**无任何 `org/kill-bill` 制品**，也无 felix、无 sofa-ark —— 首次构建全量下载
