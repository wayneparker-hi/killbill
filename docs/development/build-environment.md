# 构建环境说明

## 硬性要求

| 项 | 要求 | 本机现状 |
|---|---|---|
| JDK | **21**（`project.build.targetJdk`） | Zulu 21.0.11 ✅ |
| Maven | 3.6.3+ | 3.6.3 ✅ |
| 本地仓库 | — | `/Users/wayne/maven/repository`（10G） |

---

## ⚠️ 坑 1：jenv shim 会覆盖 JAVA_HOME

本机 `mvn` 指向 `~/.jenv/shims/mvn`，**jenv shim 会用自己的版本覆盖你 export 的 `JAVA_HOME`**。jenv 全局默认是 JDK 8，导致：

```
sortpom/SortMojo has been compiled by a more recent version of the Java Runtime
(class file version 55.0), this version of the Java Runtime only recognizes
class file versions up to 52.0
```

`export JAVA_HOME=...` **对 jenv shim 无效**。

### 解决方式（二选一）

**A. 绕开 shim，直接调真实 maven（推荐用于脚本/自动化）**

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 21)
/Users/wayne/maven/apache-maven-3.6.3/bin/mvn -v   # 确认 Java version: 21.0.11
```

**B. 用 jenv 切换（推荐用于交互式终端）**

```bash
jenv shell 21
```

---

## ⚠️ 坑 2：settings.xml 的 `<mirrorOf>*</mirrorOf>`

`~/.m2/settings.xml`：

```xml
<mirror>
    <id>nexus-aliyun</id>
    <mirrorOf>*</mirrorOf>
    <url>http://maven.aliyun.com/nexus/content/groups/public</url>
</mirror>
```

`*` 会**屏蔽所有其他仓库，包括 sonatype snapshots**。Kill Bill 官方 CI 是显式开启 `sonatypeSnapshots` 的。

若后续引入的依赖（如 SOFAArk 的某些版本）只在 snapshot 仓库存在，需改成：

```xml
<mirrorOf>*,!sonatype-nexus-snapshots</mirrorOf>
```

---

## 构建命令

### 全量构建（跳过测试与静态检查，最快拿到编译信号）

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 21)
/Users/wayne/maven/apache-maven-3.6.3/bin/mvn -B -ff -T 1C clean install \
  -DskipTests=true \
  -Dcheck.skip-spotbugs=true -Dcheck.skip-dependency=true \
  -Dcheck.skip-dependency-scope=true -Dcheck.skip-dependency-versions=true \
  -Dcheck.skip-duplicate-finder=true -Dcheck.skip-enforcer=true -Dcheck.skip-rat=true
```

参考耗时：**73 个模块，3 分 17 秒**（`-T 1C`，依赖已缓存）。

### 跑测试

测试用 TestNG group 划分，由 profile 控制：

```bash
mvn test                        # default profile → group: fast
mvn test -Ptravis               # group: fast,slow
mvn test -Pmysql                # group: fast,slow,mysql
mvn test -Pintegration-mysql    # group: fast,slow,mysql,integration
```

完整 profile 列表见 [`../analysis/01-maven-structure.md`](../analysis/01-maven-structure.md#六profiles--ci)。

---

## ⚠️ 坑 3：sortpom 会自动重写你的 pom

`sortpom-maven-plugin` 绑在 **`verify` 阶段**执行 `sort` goal，配置为：

- `nrOfIndentSpace=4`
- `spaceBeforeCloseEmptyElement=true`
- `sortDependencies=groupId,artifactId,scope`

任何 `mvn install` / `mvn verify` 都会重排 pom.xml。**手改 pom 后先跑一次构建再提交**，否则 diff 里会混入大量重排噪音。

（实测：Phase 0 的手工编辑——`<relativePath>` 与版本属性——都被完整保留，sortpom 只做排序不改语义。）

---

## 常用检查开关

oss-parent 定义了一整套 `check.*` 属性：

```
-Dcheck.skip-spotbugs=true
-Dcheck.skip-dependency=true
-Dcheck.skip-dependency-scope=true
-Dcheck.skip-dependency-versions=true
-Dcheck.skip-duplicate-finder=true
-Dcheck.skip-enforcer=true
-Dcheck.skip-rat=true
```

官方 CI 用的是 `MAVEN_FLAGS: "-B -ff -Dcheck.skip-spotbugs=true -DskipTests=true"`。

---

## 模块构建顺序

顶级 `pom.xml` 的 `<modules>` 顺序即依赖拓扑，**不要重排**：

```
killbill-oss-parent → killbill-api → killbill-plugin-api
                   → killbill-commons → lpr → killbill-platform → killbill → plugins
```

`killbill-platform` 内部：`plugin-api` → `plugin-runtime` → `platform-test`
（`platform-test` 装配 `PluginRuntimeModule`，所以必须排在它之后）。

---

## ⚠️ 坑 4：嵌入式 MySQL 在 Apple Silicon 上不可用

**现象**：任何带 DB 的测试在 `@BeforeSuite` 就挂：

```
java.io.IOException: java.lang.RuntimeException: archive not found: /mysql-Mac_OS_X-aarch64.tar.gz
	at org.killbill.commons.embeddeddb.mysql.MySQLEmbeddedDB.start
	at org.killbill.billing.platform.test.PlatformDBTestingHelper.start
	at GuicyKillbillTestSuiteWithEmbeddedDB.beforeSuite
```

默认测试 DB 是嵌入式 MySQL，而它没有 aarch64 的二进制包。**整个 suite 会被跳过**（`Tests run: 139, Skipped: 138`），
所以这不是"少跑几个测试"，而是所有 DB 相关测试都跑不了。

### 解决：用 OrbStack dev-infra 的 PostgreSQL

`PlatformDBTestingHelper` 支持连外部 DB（`isUsingLocalInstance()`）。注意在这个模式下它
**不会自动建表**（`start()` 提前 return），schema 必须预先准备好。

**一次性准备**：

```bash
# 1) 建库
docker exec dev-shared-postgres psql -U dev -d dev -c "CREATE DATABASE killbill"

# 2) PG 特定 DDL 需要 superuser（对应 executeEngineSpecificScripts 做的事）
docker exec dev-shared-postgres psql -U dev -d dev -c "ALTER ROLE dev WITH SUPERUSER"

# 3) 应用 schema。顺序必须与 DBTestingHelper.executePostStartupScripts 一致：
#    先 org/killbill/billing/util/ddl-postgresql.sql，
#    再按 util, catalog, account, analytics, beatrix, subscription, payment,
#         invoice, entitlement, usage, meter, tenant 逐包，
#    每包内 ddl_test.sql 在前、ddl.sql 在后（主 DDL 优先）
docker exec -i dev-shared-postgres psql -U dev -d killbill < <生成的 ddl.sql>
```

> 官方的 `killbill/bin/db-helper` 也能生成，但它依赖 **gnu-getopt**（macOS 自带的 BSD getopt 不行），
> 且会改写 `~/.pgpass`。本机 `/usr/local/opt/gnu-getopt` 是悬空 symlink，实际没装。

**跑测试**：

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 21)
ARGLINE="-Xmx1024m --illegal-access=permit \
  --add-opens java.base/java.lang=ALL-UNNAMED --add-opens java.base/java.io=ALL-UNNAMED \
  --add-opens java.base/java.util=ALL-UNNAMED --add-opens java.base/java.base=ALL-UNNAMED \
  -Dorg.killbill.billing.dbi.test.postgresql=true \
  -Dorg.killbill.billing.dbi.test.useLocalDb=true \
  -Dorg.killbill.billing.dbi.test.localDb.database=killbill \
  -Dorg.killbill.billing.dbi.test.localDb.username=dev \
  -Dorg.killbill.billing.dbi.test.localDb.password=dev"

mvn test -pl <module> -DsurefireArgLine="$ARGLINE"
```

日志里出现 `Using PostgreSQL local database` 才说明生效了。

### ⚠️ 坑 4a：`-D` 属性传不进 surefire fork 的 JVM

直接 `mvn test -Dorg.killbill.billing.dbi.test.postgresql=true` **不生效**——日志仍显示
`Using MySQL as the embedded database`。测试跑在 surefire fork 的独立 JVM 里，命令行 `-D` 不会自动转发。

必须通过 `-DsurefireArgLine=...` 传（oss-parent 的 surefire 配置是 `<argLine>${surefireArgLine}</argLine>`）。

**覆盖时要连原值一起带上**：JDK 17+ 的 `javac17-release` profile 会把 `surefireArgLine` 设成带四个
`--add-opens` 的版本，直接覆盖会把它们丢掉，导致另一批测试因反射受限而失败。

### 测试共用一个库，不要并行

多个模块的测试会读写同一个 `killbill` 库。跑多模块时**不要加 `-T`**，否则会互相干扰。
需要重置时用 `truncate` 而不是重建（表结构不变）。

---

## ⚠️ 坑 5：嵌入式 Redis 同样在 Apple Silicon 上启不来

**现象**：`killbill-commons/clock` 的 `TestDistributedClockMock` 在 `@BeforeMethod` 挂掉：

```
java.lang.RuntimeException: Can't start redis server. Check logs for details.
	at redis.embedded.AbstractRedisInstance.awaitRedisServerReady
	at org.killbill.clock.TestDistributedClockMock.setUp
```

与[坑 4](#-坑-4嵌入式-mysql-在-apple-silicon-上不可用) 同一类问题：`redis-embedded` 内置的
redis 二进制没有 aarch64 版本。

**注意**：这与 dev-infra 里跑着的 Redis 无关——该测试用的是**嵌入式** Redis，不读外部地址，
所以起一个 docker Redis 并不能让它通过。

**现状**：这是既有的环境限制，与本次改造无关（`killbill-commons` 未被修改）。
跑全量测试时用 `-fae`（fail at end）以免它挡住后面的模块，或单独排除该测试类。

---

## 本机能跑与不能跑的测试

`killbill-commons` 的一部分测试在 Apple Silicon 上无法运行，因为它们依赖没有 aarch64 二进制的
嵌入式数据库。这与本次改造无关（`killbill-commons` 未被修改），但会挡住整个 reactor：
失败模块的下游会被 `-fae` 标记为 SKIPPED。

**跑不了的**（全部在 `killbill-commons`）：

| 模块 | 测试 | 原因 |
|---|---|---|
| `clock` | `TestDistributedClockMock` | 嵌入式 Redis 无 aarch64 二进制 |
| `embeddeddb/mysql` | `TestMySqlEmbeddedDB` | 嵌入式 MySQL 无 aarch64 二进制 |
| `locker` | `TestMysqlGlobalLocker` | 同上 |
| `queue` | **整个套件**（基类 `TestSetup` 用嵌入式 MySQL） | 同上 |
| `jdbi` | `TestHandle.testSillyNumberOfCallbacks` | 既有失败：`UnsupportedOperationException: Not supported yet` |

**推荐做法：按子树分别跑**，而不是在全 reactor 里逐个排除测试类：

```bash
for sub in lpr killbill-platform killbill plugins; do
  (cd $sub && mvn -B -fae test -DsurefireArgLine="$ARGLINE")
done
```

每个子树的 pom 都通过 `<relativePath>` 找到 `killbill-oss-parent`，所以单独进入子目录构建是可行的；
依赖的 `killbill-commons` 从本地仓库取已安装的制品。

这样得到的信号是干净的：**跑的是自己改动范围内的测试，环境限制不会伪装成回归。**

### ⚠️ 坑 6：JVM 语言环境是中文，测试断言的是英文报错

`beatrix/TestCatalogValidation#testValidateCatalog` 会这样失败：

```
expected [cvc-complex-type.2.4.a: Invalid content was found starting with element 'duration'. ...]
but found [cvc-complex-type.2.4.a: 发现了以元素 'duration' 开头的无效内容。...]
```

测试直接断言 XSD 校验器的英文原文，而 JDK 会按 `user.language` 返回本地化消息。

**解决**：在 `surefireArgLine` 里固定语言环境。

```
-Duser.language=en -Duser.country=US
```

加上之后同一个测试通过。这条对**任何断言 JDK/第三方库异常文案**的测试都成立，
不止 catalog 这一处。

### ⚠️ 坑 7：PostgreSQL 的序列不会随测试清库回退

`profiles/killbill` 的 `TestEntitlement#testCreateSubscriptionWithOnlyUsageOverrides` 会失败：

```
expected [trebuchet-usage-in-arrear-1] but found [trebuchet-usage-in-arrear-37]
```

**这个数字每跑一次就加一**——单独跑也一样，所以不是测试顺序问题。

根因在 `DefaultPriceOverrideSvc.java:124`：覆盖计划的名字里内嵌了数据库 `record_id`。

```java
planName = new StringBuffer(parentPlan.getName()).append("-").append(overriddenPlan.getRecordId()).toString();
```

测试断言 `-1`，即**假定数据库是全新的**。官方 CI 每次跑都起一个干净的嵌入式 MySQL，
`AUTO_INCREMENT` 自然从 1 开始；我们复用一个常驻的 PostgreSQL，
而测试清库用的是 `DELETE`，PostgreSQL 的 sequence 不会因此回退。

**已修复**（2026-09）：跑之前复位序列**没用**——套件自己在运行过程中会消耗序列，
等跑到 `profiles/killbill` 时早已不是 1。

真正的修法是改测试：那条断言里"是派生名"是被测代码的契约，"序列等于 1"是环境巧合。
现在断言 `trebuchet-usage-in-arrear-\d+`，验证的东西一点没少，且不再依赖运行环境。

如果确实需要一个干净的序列（比如复现官方 CI 的行为）：

```bash
PGPASSWORD=dev psql -h localhost -U dev -d killbill \
  -c "TRUNCATE catalog_override_plan_definition RESTART IDENTITY CASCADE;"
```

**更一般的教训**：**复用常驻数据库时，第一类会漂移的就是自增序列。**
凡是断言里出现"从 1 开始的编号"的测试，都要先怀疑序列没复位，
而不是怀疑自己的改动。判别方法很便宜——**连跑两次，看数字是否递增**。

### 一个 PostgreSQL 下才暴露的既有测试问题

`TestInternalCallContextFactory#testCreateInternalCallContextWithAccountRecordIdFromSimpleObjectType`
在 PostgreSQL 上失败：

```
PSQLException: ERROR: null value in column "grp_id" of relation "invoices" violates not-null constraint
```

**不是回归，且已修复**（2026-09）。该测试用 raw SQL 插入 `invoices`，语句里**省略了 `grp_id`**，而
`killbill/invoice/.../ddl.sql` 把它声明为 `varchar(36) NOT NULL` 且无默认值。
MySQL 在非严格模式下会给它塞一个空串，PostgreSQL 直接拒绝——
**省略一个 NOT NULL 无默认值的列在任何严格模式的数据库上都是错的**，所以改了测试而非绕过。

修掉它的收益不止一个测试：`-fae` 下它会让 15 个下游模块 SKIPPED。

顺带确认了建库顺序是对的：`ddl_test.sql` 和 `ddl.sql` 开头都有 `DROP TABLE IF EXISTS`，
所以同名表**后写入的胜出**——这与官方 `bin/db-helper` 的注释
（"Test DDL first as the main DDL takes precedence in case of dups"）以及
`DBTestingHelper.executePostStartupScripts()` 的顺序一致。
也就是说 `invoices` 确实带 `grp_id NOT NULL`，官方的 MySQL 测试环境同样如此，只是 MySQL 不较真。

跑 killbill 子树时按方法排除即可，该类其余测试仍会执行：

```bash
mvn -fae test -Dtest='!TestInternalCallContextFactory#testCreateInternalCallContextWithAccountRecordIdFromSimpleObjectType' \
              -Dsurefire.failIfNoSpecifiedTests=false
```
