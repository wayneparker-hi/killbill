# Lightweight Plugin Runtime

## 轻量级 Java 插件运行时架构规格 v1.0

------

# 1. 设计目标

## 1.1 项目定位

`Light Plugin Runtime`（以下简称 LPR）是一个运行在单 JVM 内的、面向 Java 平台的动态插件运行时。

它解决的问题不是：

> 如何让 Java 应用加载一个 Jar？

而是：

> 如何让一个长期演进的 Platform Core，在不修改核心代码的情况下，动态发现、加载、运行、管理和替换第三方扩展模块。

核心能力：

```text
Plugin Discovery
Plugin Loading
ClassLoader Isolation
Plugin Lifecycle
Service Registry
Event Subscription
Configuration
Version Management
Health Management
Plugin Administration
```

------

# 2. 非目标

必须明确哪些东西**不做**。

LPR 不试图实现：

```text
OSGi 完整规范
Package Wiring
Bundle Fragment
完整 Module Resolution
JVM Security Sandbox
跨进程隔离
独立进程部署
分布式 Service Discovery
容器级资源隔离
```

因此：

> LPR 是 Plugin Runtime，不是 OSGi Runtime。

如果 Plugin 是恶意代码，LPR 无法保证 JVM 内安全隔离。

需要强隔离时：

```text
Plugin
  ↓
独立 JVM / Container
```

而不是继续堆 ClassLoader 技术。

------

# 3. 核心设计原则

## 3.1 API First

Core 与 Plugin 之间只能通过稳定 API 通信。

禁止：

```text
Plugin
  ↓
Core Internal Class
```

允许：

```text
Plugin
  ↓
Plugin API
  ↓
Core
```

------

## 3.2 Runtime 与 Business Core 分离

```text
Business Core
       ↑
Platform API
       ↑
Plugin Runtime
       ↑
Plugin
```

Plugin Runtime 不应该知道：

```text
Order
Invoice
Payment
Subscription
```

这些属于业务层。

------

## 3.3 Plugin 是独立模块，而不是 Bean

Plugin：

```text
Jar
+
独立 ClassLoader
+
独立依赖
+
生命周期
+
Service
+
Event
+
Configuration
```

因此不能把 Plugin 简化成：

```java
@Component
class MyPlugin
```

Spring Bean 可以成为 Plugin 的内部实现，但不能成为 Plugin Runtime 的基本抽象。

------

## 3.4 所有动态能力都必须有状态机

禁止：

```java
start();
stop();
```

这种没有状态约束的实现。

所有 Plugin 操作必须经过：

```text
State
 → Command
 → Validation
 → Transition
 → Side Effect
 → New State
```

------

# 4. 总体架构

```text
                           Management API
                                │
                                ▼
                     ┌─────────────────────┐
                     │    PluginManager    │
                     └──────────┬──────────┘
                                │
       ┌────────────────────────┼────────────────────────┐
       │                        │                        │
       ▼                        ▼                        ▼
Plugin Repository       Lifecycle Manager        Version Manager
       │                        │                        │
       └────────────────────────┼────────────────────────┘
                                ▼
                     ┌─────────────────────┐
                     │   Plugin Runtime    │
                     └──────────┬──────────┘
                                │
        ┌───────────────────────┼───────────────────────┐
        │                       │                       │
        ▼                       ▼                       ▼
 ClassLoader              Service Registry           Event Bus
        │                       │                       │
        └───────────────────────┼───────────────────────┘
                                ▼
                         Plugin Instances
                                │
                                ▼
                         Platform API
                                │
                                ▼
                           Core System
```

------

# 5. Maven Module 结构

建议第一版直接采用 Maven Multi-Module：

```text
light-plugin-runtime/
│
├── plugin-api/
│
├── plugin-core/
│
├── plugin-classloader/
│
├── plugin-registry/
│
├── plugin-lifecycle/
│
├── plugin-event/
│
├── plugin-config/
│
├── plugin-management/
│
├── plugin-testkit/
│
└── plugin-example/
```

依赖关系：

```text
plugin-api
    ↑
    │
plugin-classloader
plugin-registry
plugin-event
plugin-config
plugin-lifecycle
    ↑
    │
plugin-core
    ↑
    │
plugin-management
```

关键原则：

```text
plugin-api
    ↓
不能依赖 plugin-core
```

Plugin API 必须成为最稳定的公共契约。

------

# 6. Plugin API

最核心接口：

```java
public interface Plugin {

    void start(PluginContext context) throws Exception;

    void stop() throws Exception;
}
```

这是 Plugin 生命周期入口。

------

# 7. PluginContext

Plugin 不能直接访问 Runtime 内部对象。

只能通过 Context 获得能力。

```java
public interface PluginContext {

    String pluginId();

    String version();

    PluginConfig config();

    ServiceRegistry services();

    EventBus eventBus();

    <T> T getPlatformService(Class<T> type);
}
```

这里实际上形成一个 Capability Boundary：

```text
Plugin
 │
 └── PluginContext
       ├── Config
       ├── Service
       ├── Event
       └── Platform API
```

Plugin 不应该拿到：

```text
PluginManager
PluginClassLoader
PluginRepository
LifecycleManager
```

否则 Plugin 可以反向控制 Runtime。

------

# 8. Plugin Descriptor

每个 Plugin 必须有一个描述文件：

```text
plugin.yaml
```

示例：

```yaml
id: stripe-payment

name: Stripe Payment Plugin

version: 1.2.0

entrypoint:
  class: com.example.stripe.StripePlugin

runtime:
  java: ">=21"

requires:
  platform-api: ">=1.0.0"

provides:
  services:
    - com.example.payment.PaymentPluginApi

  events:
    - PaymentEvent

config:
  required:
    - stripe.apiKey

  optional:
    - stripe.timeout
```

------

# 9. Plugin ID

Plugin ID 必须全局唯一。

规则：

```text
[a-z0-9][a-z0-9.-]*
```

例如：

```text
stripe-payment
aliyun-payment
tax-engine
notification-email
```

禁止使用：

```text
StripePlugin
com.foo.Plugin
plugin1
```

因为 Plugin ID 是 Runtime 中的稳定身份，而不是 Java Class 名。

------

# 10. Plugin Artifact

目录结构：

```text
plugins/
└── stripe-payment/
    ├── 1.1.0/
    │   ├── plugin.jar
    │   ├── plugin.yaml
    │   └── lib/
    │       ├── stripe-sdk.jar
    │       └── jackson.jar
    │
    └── 1.2.0/
        ├── plugin.jar
        ├── plugin.yaml
        └── lib/
```

运行时状态：

```text
plugins/
└── stripe-payment/
    ├── 1.1.0/
    ├── 1.2.0/
    └── ACTIVE
```

`ACTIVE` 可以是：

```text
symbolic link
```

或者：

```text
metadata.json
```

但不要依赖文件名本身表达状态。

------

# 11. Plugin Lifecycle

定义：

```text
                    INSTALL
                       │
                       ▼
                  INSTALLED
                       │
                    RESOLVE
                       │
                       ▼
                  RESOLVED
                       │
                     START
                       │
                       ▼
                    STARTING
                       │
                       ▼
                    ACTIVE
                       │
                  ┌────┴────┐
                  │         │
                STOP      FAILURE
                  │         │
                  ▼         ▼
               STOPPING   FAILED
                  │
                  ▼
                STOPPED
                  │
               UNINSTALL
                  │
                  ▼
             UNINSTALLED
```

------

# 12. Lifecycle 状态定义

```java
public enum PluginState {

    INSTALLED,
    RESOLVED,
    STARTING,
    ACTIVE,
    STOPPING,
    STOPPED,
    FAILED,
    UNINSTALLED
}
```

状态转换必须集中管理：

```java
public interface PluginLifecycleManager {

    void start(String pluginId);

    void stop(String pluginId);

    void restart(String pluginId);

    PluginState state(String pluginId);
}
```

禁止 Plugin 自己修改状态。

------

# 13. PluginClassLoader

这是整个项目最核心的技术组件。

定义：

```java
public final class PluginClassLoader
        extends URLClassLoader {
}
```

但真正重要的不是继承 `URLClassLoader`，而是**Class Loading Policy**。

------

# 14. ClassLoader 委派模型

采用：

> API Parent-First + Plugin Dependency Child-First

逻辑：

```text
loadClass(name)
       │
       ├── JDK class?
       │       ↓
       │    Parent
       │
       ├── Plugin API?
       │       ↓
       │    Parent
       │
       ├── Platform API?
       │       ↓
       │    Parent
       │
       └── Otherwise
               ↓
         Plugin ClassPath
```

例如：

```text
Parent
├── java.*
├── javax.*
├── org.lpr.api.*
└── org.tik.platform.api.*

Plugin
├── com.stripe.*
├── com.fasterxml.jackson.*
└── com.stripe.sdk.*
```

这样 Plugin 可以：

```text
自己的 Jackson
自己的 HTTP Client
自己的 SDK
```

但必须使用 Core 提供的：

```text
Plugin API
Platform API
```

------

# 15. 为什么不能全部 Child-First？

因为会导致 API 类型不一致。

例如：

```text
Core:
PaymentPluginApi.class

Plugin:
PaymentPluginApi.class
```

两个 ClassLoader 加载后：

```text
Core PaymentPluginApi
!=
Plugin PaymentPluginApi
```

最终：

```text
ClassCastException
```

所以公共 API 必须由 Parent ClassLoader 加载。

------

# 16. Service Registry

核心接口：

```java
public interface ServiceRegistry {

    <T> ServiceRegistration register(
        Class<T> type,
        T service
    );

    <T> Optional<T> getService(
        Class<T> type
    );

    <T> List<ServiceReference<T>> getServices(
        Class<T> type
    );
}
```

Service Reference：

```java
public interface ServiceReference<T> {

    String pluginId();

    String version();

    T service();

    Map<String, Object> properties();
}
```

------

# 17. Service Registry 数据模型

内部：

```text
ServiceRegistry
       │
       ▼
Map<ServiceType, ServiceBucket>
                       │
                       ▼
                ServiceReference
                  ├── pluginId
                  ├── version
                  ├── service
                  └── properties
```

例如：

```text
PaymentPluginApi

├── stripe-payment
│     version=1.2.0
│     priority=100
│
├── adyen-payment
│     version=2.0.0
│     priority=80
│
└── paypal-payment
      version=1.4.0
      priority=70
```

------

# 18. Service Selection

不要简单：

```java
getService(PaymentPluginApi.class);
```

因为可能存在多个实现。

应该支持：

```java
<T> Optional<T> getService(
    Class<T> type,
    ServiceFilter filter
);
```

例如：

```java
registry.getService(
    PaymentPluginApi.class,
    ServiceFilter.property(
        "provider",
        "stripe"
    )
);
```

或者：

```text
provider = stripe
tenant = xxx
priority > 50
```

------

# 19. Service Registration 生命周期

Service 的生命周期必须绑定 Plugin：

```text
Plugin STARTING
      ↓
register services
      ↓
Plugin ACTIVE
```

停止：

```text
Plugin STOPPING
      ↓
unregister services
      ↓
Plugin STOPPED
```

绝对不能允许：

```text
Plugin STOPPED
      ↓
Service Registry
      ↓
仍然返回 Plugin Service
```

否则会出现典型的：

```text
Stale Service
ClassLoader Leak
Dead Plugin Reference
```

------

# 20. Event Bus

定义：

```java
public interface EventBus {

    void publish(Object event);

    <T> Subscription subscribe(
        Class<T> eventType,
        EventHandler<T> handler
    );
}
```

Event Handler：

```java
@FunctionalInterface
public interface EventHandler<T> {

    void handle(T event);
}
```

------

# 21. Event Bus 的一个重要原则

Event Bus 不负责业务可靠性。

第一版可以：

```text
In-Memory
Synchronous / Async
```

但不要声称：

```text
Exactly Once
Durable
Guaranteed Delivery
```

如果业务需要可靠事件：

```text
Domain Event
      ↓
Outbox
      ↓
Kafka
      ↓
Plugin
```

LPR Event Bus 只负责：

> **进程内 Plugin Event Dispatch。**

这是非常重要的边界。

------

# 22. Event Subscription 生命周期

Plugin 启动：

```text
start()
   ↓
subscribe()
```

Plugin 停止：

```text
stop()
   ↓
unsubscribe()
```

因此 PluginContext 应该能够统一托管资源：

```java
public interface PluginContext {

    ResourceRegistry resources();

}
```

所有：

```text
ServiceRegistration
Subscription
ScheduledTask
Executor
HTTP Client
```

都应该进入 ResourceRegistry。

------

# 23. Resource Registry

这是我建议在第一版就加入的设计。

```java
public interface ResourceRegistry {

    <T extends AutoCloseable>
    T manage(T resource);

    void closeAll();
}
```

Plugin：

```java
public void start(PluginContext context) {

    EventSubscription subscription =
        context.eventBus()
               .subscribe(...);

    context.resources()
           .manage(subscription);
}
```

停止：

```java
context.resources().closeAll();
```

这样可以显著降低：

```text
线程泄漏
连接泄漏
Event Subscription 泄漏
ClassLoader 泄漏
```

------

# 24. Plugin Configuration

定义：

```java
public interface PluginConfig {

    String get(String key);

    Optional<String> find(String key);

    <T> T get(
        String key,
        Class<T> type
    );

    Map<String, Object> all();
}
```

配置层次：

```text
System Config
      │
      ↓
Plugin Config
      │
      ↓
Tenant Config
```

不要让 Plugin 直接：

```java
System.getenv()
System.getProperty()
```

否则 Runtime 无法管理配置。

------

# 25. Plugin Health

定义：

```java
public interface PluginHealth {

    HealthStatus health();

    HealthStatus readiness();
}
```

例如：

```text
ACTIVE
  │
  ├── health = UP
  └── readiness = READY
```

Stripe：

```text
ACTIVE
health = DOWN
reason = API unavailable
```

Plugin 仍然可以保持加载状态，但不参与业务流量。

------

# 26. Plugin Version

版本采用：

```text
Semantic Versioning
```

例如：

```text
1.2.0
1.3.0
2.0.0
```

Descriptor：

```yaml
requires:
  platform-api: ">=1.0.0 <2.0.0"
```

第一版不要实现复杂依赖解析。

只支持：

```text
Plugin → Platform API
```

不支持：

```text
Plugin A → Plugin B → Plugin C
```

原因很简单：

> Plugin Dependency Graph 会迅速把这个项目重新拖回 OSGi。

------

# 27. Plugin Update

更新采用：

> **Side-by-Side Loading + Atomic Service Switch**

例如：

```text
stripe 1.2.0
     │
     │
     ├───────────────┐
     │               │
     ▼               ▼
 ACTIVE           Load 1.3.0
                     │
                     ▼
                  Resolve
                     │
                     ▼
                    Start
                     │
                     ▼
                   Health
                     │
                     ▼
                   READY
                     │
                     ▼
              Atomic Switch
                     │
          ┌──────────┴─────────┐
          ▼                    ▼
      1.3.0 ACTIVE          1.2.0
                              │
                              ▼
                             STOP
```

这是整个 Runtime 中非常关键的设计。

------

# 28. 为什么不能 Stop → Load → Start？

因为：

```text
Stop 1.2
    ↓
Load 1.3
    ↓
Start 1.3
```

中间存在：

```text
Availability Gap
```

如果 Plugin 是：

```text
Payment
Tax
Authentication
```

这个窗口可能直接导致业务失败。

所以采用：

```text
Parallel Load
+
Readiness Check
+
Atomic Switch
```

------

# 29. Atomic Service Switch

Service Registry 必须支持：

```java
void replace(
    ServiceKey key,
    ServiceReference<?> oldService,
    ServiceReference<?> newService
);
```

本质：

```text
Before:

PaymentService
      ↓
Stripe 1.2

After:

PaymentService
      ↓
Stripe 1.3
```

切换必须是一个原子操作。

------

# 30. Rollback

如果：

```text
1.3.0
  ↓
START
  ↓
READY
  ↓
traffic
  ↓
runtime failure
```

必须能够：

```text
1.3.0
  ↓
Deactivate
  ↓
1.2.0
  ↓
Activate
```

因此 Runtime 必须保留：

```text
previousVersion
activeVersion
candidateVersion
```

------

# 31. PluginManager

最终公开 API：

```java
public interface PluginManager {

    void install(Path artifact);

    void uninstall(String pluginId);

    void start(String pluginId);

    void stop(String pluginId);

    void restart(String pluginId);

    void update(
        String pluginId,
        Path artifact
    );

    void rollback(String pluginId);

    PluginInfo get(String pluginId);

    List<PluginInfo> list();
}
```

------

# 32. PluginManager 内部结构

```text
PluginManager
│
├── PluginRepository
│
├── PluginDescriptorParser
│
├── PluginResolver
│
├── PluginLoader
│
├── PluginLifecycleManager
│
├── PluginRegistry
│
├── ServiceRegistry
│
├── EventBus
│
├── ConfigManager
│
├── HealthManager
│
└── VersionManager
```

------

# 33. PluginLoader

核心职责：

```text
Artifact
   ↓
Descriptor
   ↓
ClassLoader
   ↓
Entrypoint
   ↓
Plugin Instance
```

接口：

```java
public interface PluginLoader {

    LoadedPlugin load(
        PluginArtifact artifact
    );

    void unload(
        LoadedPlugin plugin
    );
}
```

------

# 34. LoadedPlugin

```java
public final class LoadedPlugin {

    private final PluginDescriptor descriptor;

    private final PluginClassLoader classLoader;

    private final Plugin instance;

}
```

它代表：

> **一个已经被 Runtime 加载但不一定 ACTIVE 的 Plugin 实例。**

------

# 35. Plugin Registry

Runtime 必须维护完整状态：

```java
public interface PluginRegistry {

    Optional<PluginRuntime> get(String pluginId);

    Collection<PluginRuntime> all();
}
```

Runtime：

```text
PluginRuntime
├── PluginDescriptor
├── PluginState
├── PluginClassLoader
├── PluginInstance
├── PluginContext
├── Services
├── Subscriptions
├── Health
└── Metrics
```

------

# 36. Plugin 安装流程

```text
                plugin.jar
                    │
                    ▼
               Validate
                    │
                    ▼
             Read Descriptor
                    │
                    ▼
             Validate ID
                    │
                    ▼
            Validate Version
                    │
                    ▼
            Validate Java API
                    │
                    ▼
                Install
                    │
                    ▼
               INSTALLED
```

------

# 37. Plugin Start 流程

```text
START
  │
  ▼
Load Descriptor
  │
  ▼
Create ClassLoader
  │
  ▼
Load Entrypoint
  │
  ▼
Create PluginContext
  │
  ▼
Plugin.start()
  │
  ├── register services
  ├── subscribe events
  └── initialize resources
  │
  ▼
Health Check
  │
  ▼
READY
  │
  ▼
ACTIVE
```

------

# 38. Plugin Stop 流程

```text
STOP
 │
 ▼
STOPPING
 │
 ├── Disable new traffic
 │
 ├── Remove service bindings
 │
 ├── Unsubscribe events
 │
 ├── Plugin.stop()
 │
 ├── Close resources
 │
 └── Close ClassLoader
 │
 ▼
STOPPED
```

顺序非常重要。

尤其是：

> **先切断新流量，再关闭资源。**

否则可能出现：

```text
request
 ↓
service
 ↓
plugin
 ↓
ClassLoader already closed
```

------

# 39. ClassLoader 卸载

Java 中没有：

```java
classLoader.unload();
```

真正的卸载条件是：

```text
ClassLoader
    ↓
没有 GC Root 引用
    ↓
GC
    ↓
Class Metadata 回收
```

因此 Plugin Stop 后必须清理：

```text
Thread
Executor
Timer
Static Reference
ThreadLocal
Event Subscription
Service Registry
JDBC Driver
MBean
Callback
```

这也是为什么 Resource Registry 必须成为 Runtime 的一等公民。

------

# 40. Thread 管理

Plugin 禁止：

```java
new Thread(...)
```

建议通过：

```java
PluginExecutor
```

统一创建。

```java
public interface PluginExecutor {

    ExecutorService executor();

    ScheduledExecutorService scheduler();
}
```

Runtime 在 Stop 时：

```text
Plugin STOP
   ↓
shutdown executor
   ↓
await termination
   ↓
force shutdown
```

否则 Plugin ClassLoader 很容易因为线程栈引用而无法回收。

------

# 41. Plugin API 与 Core API

最终形成两套边界：

```text
                Platform
                   │
        ┌──────────┴──────────┐
        ↓                     ↓
    Core API             Plugin SPI
        │                     │
        ↓                     ↓
     Plugin                Core
```

更准确：

```text
Core ──────→ Plugin SPI
Plugin ────→ Platform API
```

例如：

```java
public interface PaymentPluginApi {

    PaymentResult authorize(
        PaymentRequest request
    );

    PaymentResult capture(
        PaymentRequest request
    );

    PaymentResult refund(
        PaymentRequest request
    );
}
```

这是：

> Core → Plugin

而：

```java
public interface PaymentService {

    Payment getPayment(String id);

}
```

这是：

> Plugin → Core

两者必须分开。

------

# 42. 业务 Plugin 的典型结构

例如：

```text
stripe-payment/
│
├── plugin.yaml
│
├── plugin.jar
│
└── lib/
    ├── stripe-sdk.jar
    ├── jackson.jar
    └── http-client.jar
```

代码：

```text
com.example.stripe
│
├── StripePlugin
├── StripePaymentPluginApi
├── StripeClient
├── StripeConfig
├── StripeWebhookHandler
└── StripeHealthCheck
```

Plugin 本身只依赖：

```text
plugin-api
platform-api
```

第三方 SDK 完全属于 Plugin 自己。

------

# 43. 多租户

如果平台本身是 SaaS：

```text
Tenant
   │
   └── Plugin Configuration
```

不要为每个 Tenant 创建 Plugin 实例。

默认：

```text
1 Plugin Instance
      ↓
N Tenant
```

例如：

```text
StripePlugin
   │
   ├── Tenant A → API Key A
   ├── Tenant B → API Key B
   └── Tenant C → API Key C
```

PluginContext 应提供：

```java
TenantContext
```

而不是把 Tenant 配置硬编码进 Plugin Instance。

------

# 44. Plugin Routing

真正的生产系统通常存在：

```text
PaymentPlugin A
PaymentPlugin B
PaymentPlugin C
```

因此应该引入：

```text
Capability
+
Selector
```

例如：

```text
tenant = SG001
currency = SGD
paymentMethod = CARD
```

最终：

```text
PluginRouter
      ↓
Stripe
```

而不是让业务 Core：

```java
if ("stripe".equals(provider)) {
    ...
}
```

这样才能真正做到：

> Core 与具体 Plugin 实现解耦。

------

# 45. Capability Model

Descriptor：

```yaml
provides:
  - capability: payment
    provider: stripe
    methods:
      - card
      - wallet
```

Runtime 注册：

```text
CapabilityRegistry

payment
 ├── stripe
 ├── adyen
 └── paypal
```

业务层只表达：

```text
PaymentRequirement
```

例如：

```text
currency = SGD
method = CARD
```

Router 决定：

```text
Stripe
```

------

# 46. Metrics

每个 Plugin 必须有独立指标：

```text
plugin.start.count
plugin.start.failure
plugin.stop.count

plugin.request.count
plugin.request.error
plugin.request.latency

plugin.event.received
plugin.event.failed

plugin.health
plugin.readiness
```

维度：

```text
plugin_id
plugin_version
operation
```

否则生产环境无法判断：

> 是 Core 出问题，还是 Plugin 出问题。

------

# 47. Logging

Plugin 日志必须自动带：

```text
pluginId
pluginVersion
tenantId
traceId
```

例如：

```text
plugin=stripe-payment
version=1.2.0
tenant=SG001
trace=abc123
```

Plugin 不应该自己拼接这些字段。

Runtime 应通过 MDC / Context 自动注入。

------

# 48. Security

这里必须非常诚实：

```text
ClassLoader Isolation
       ≠
Security Isolation
```

Plugin 只要运行在同一个 JVM：

```java
System.exit()
Files.delete(...)
Runtime.exec(...)
```

原则上仍然存在风险。

因此：

### Trusted Plugin

```text
同 JVM
ClassLoader Isolation
```

### Untrusted Plugin

```text
独立 JVM
Container
Network Policy
Resource Limit
```

不要尝试重新实现 Java SecurityManager。

------

# 49. Plugin Management API

建议提供：

```text
GET    /plugins
GET    /plugins/{id}

POST   /plugins/install
POST   /plugins/{id}/start
POST   /plugins/{id}/stop
POST   /plugins/{id}/restart

POST   /plugins/{id}/update
POST   /plugins/{id}/rollback

DELETE /plugins/{id}
```

状态：

```json
{
  "id": "stripe-payment",
  "version": "1.2.0",
  "state": "ACTIVE",
  "health": "UP",
  "readiness": "READY"
}
```

------

# 50. 线程安全要求

所有 Runtime 核心对象必须支持并发访问：

```text
PluginRegistry
ServiceRegistry
PluginState
EventBus
```

推荐：

```text
ConcurrentHashMap
AtomicReference
ReadWriteLock
StampedLock
```

状态迁移建议使用：

```java
AtomicReference<PluginState>
```

通过 CAS 保证：

```text
START
```

不会同时执行两次。

------

# 51. 一个完整的启动过程

整个 Runtime：

```text
Application Start
       │
       ▼
PluginRuntime.initialize()
       │
       ▼
Scan Plugin Directory
       │
       ▼
Parse Descriptors
       │
       ▼
Validate Plugins
       │
       ▼
Build Plugin Registry
       │
       ▼
Resolve Active Versions
       │
       ▼
Start Plugins
       │
       ▼
Health / Readiness
       │
       ▼
Register Services
       │
       ▼
Runtime READY
```

------

# 52. 一个完整的业务调用

例如支付：

```text
Order
  │
  ▼
PaymentService
  │
  ▼
PaymentRouter
  │
  ├── tenant
  ├── currency
  └── paymentMethod
  │
  ▼
ServiceRegistry
  │
  ▼
PaymentPluginApi
  │
  ▼
Stripe Plugin
  │
  ▼
Stripe SDK
  │
  ▼
Stripe
```

Core 完全不知道：

```text
StripeClient
Stripe SDK
Jackson
HTTP Client
```

------

# 53. Event 调用

```text
PaymentSuccess
      │
      ▼
   EventBus
      │
 ┌────┼───────────┐
 ↓    ↓           ↓
CRM  Notification Analytics
```

Plugin 之间不要直接调用：

```text
Plugin A
   ↓
Plugin B
```

除非未来明确引入 Plugin Dependency。

默认：

> **Plugin 之间只能通过 Platform Service / Event Bus 间接协作。**

------

# 54. 测试体系

必须提供：

```text
plugin-testkit
```

让开发者可以：

```java
PluginTestRuntime runtime =
    PluginTestRuntime.create();

runtime.install(
    TestPlugin.class
);

runtime.start("test-plugin");

assertThat(
    runtime.state("test-plugin")
).isEqualTo(ACTIVE);
```

同时测试：

```text
Lifecycle
ClassLoader
Service
Event
Config
Version
Update
Rollback
Failure
Leak
```

------

# 55. 最重要的测试：ClassLoader Isolation

必须验证：

```text
Plugin A → Guava X
Plugin B → Guava Y
```

同时：

```text
A != B
```

并且：

```text
Plugin API
A.classloader == Core.classloader
```

但：

```text
Plugin dependency
A.classloader != B.classloader
```

这是 LPR 的核心正确性测试。

------

# 56. 最重要的测试：ClassLoader Leak

流程：

```text
Install
 ↓
Start
 ↓
Stop
 ↓
Drop Reference
 ↓
GC
 ↓
Verify ClassLoader collected
```

可以使用：

```java
WeakReference<ClassLoader>
```

验证：

```java
assertThat(reference.get())
    .isNull();
```

这个测试必须存在。

------

# 57. MVP 范围

第一版不要贪。

### P0

```text
Plugin Descriptor
Plugin Discovery
PluginClassLoader
Plugin Lifecycle
Plugin Registry
Service Registry
Plugin Context
Plugin Configuration
Basic Management API
```

### P1

```text
Event Bus
Health
Metrics
Resource Registry
Version Management
Update
Rollback
```

### P2

```text
Capability
Routing
Tenant Configuration
Hot Update
Dependency Resolution
Remote Plugin Repository
Signature Verification
```

------

# 58. 推荐的开发顺序

不要按照 Maven Module 一个个写。

按照风险排序。

## Phase 1：ClassLoader

先证明：

```text
Plugin A
  ↓
dependency version A

Plugin B
  ↓
dependency version B
```

可以共存。

------

## Phase 2：Lifecycle

实现：

```text
INSTALL
START
STOP
RESTART
FAIL
RECOVER
```

------

## Phase 3：Service Registry

实现：

```text
register
lookup
unregister
replace
```

------

## Phase 4：Resource Management

解决：

```text
Thread
Executor
Event Subscription
Connection
ClassLoader
```

生命周期问题。

------

## Phase 5：Event Bus

加入：

```text
subscribe
unsubscribe
publish
async
```

------

## Phase 6：Version / Update

最后才实现：

```text
1.2
 ↓
1.3
 ↓
health
 ↓
atomic switch
 ↓
rollback
```

------

# 59. 最终核心 API

如果压缩整个系统，实际上只有这些接口：

```text
Plugin
PluginContext

PluginManager
PluginLoader
PluginRegistry

PluginClassLoader

ServiceRegistry
ServiceReference
ServiceRegistration

EventBus
Subscription

PluginConfig
ResourceRegistry

PluginHealth
CapabilityRegistry
```

这就是整个 Runtime 的骨架。

------

# 60. 最终架构

```text
                         ┌───────────────────────┐
                         │    Management API     │
                         └───────────┬───────────┘
                                     │
                                     ▼
                         ┌───────────────────────┐
                         │     PluginManager     │
                         └───────────┬───────────┘
                                     │
             ┌───────────────────────┼───────────────────────┐
             │                       │                       │
             ▼                       ▼                       ▼
       Repository              Lifecycle               Version
             │                       │                       │
             └───────────────────────┼───────────────────────┘
                                     ▼
                         ┌───────────────────────┐
                         │   Plugin Runtime      │
                         └───────────┬───────────┘
                                     │
       ┌─────────────────────────────┼──────────────────────────┐
       │                             │                          │
       ▼                             ▼                          ▼
ClassLoader                  Service Registry               Event Bus
       │                             │                          │
       │                      ┌──────┴──────┐                   │
       │                      ↓             ↓                   │
       │                  Payment        Tax                   │
       │                  Plugin         Plugin                │
       │                      │             │                   │
       └──────────────────────┼─────────────┼───────────────────┘
                              │             │
                              ▼             ▼
                         Platform API
                              │
                              ▼
                         Business Core
```

------

# 61. 最终设计判断

这个方案和 OSGi 的关系可以概括成：

```text
                    OSGi
                      │
        ┌─────────────┼─────────────┐
        ↓             ↓             ↓
     Bundle       Service       Lifecycle
     System       Registry      Management
        │             │             │
        └─────────────┼─────────────┘
                      ↓
              Lightweight Runtime
                      │
        ┌─────────────┼─────────────┐
        ↓             ↓             ↓
 ClassLoader      Service        Lifecycle
 Isolation        Registry       Management
                      │
                      +
                 Event Bus
                      │
                      +
                Resource Mgmt
                      │
                      +
              Version / Update
```

所以 LPR 并不是：

> **“用 URLClassLoader 模拟 OSGi。”**

而应该是：

> **“重新定义一个面向现代 Java Platform 的最小 Plugin Runtime。”**

它的核心抽象只有四个：

```text
        Plugin
          │
          ▼
      PluginContext
          │
     ┌────┼────┐
     ↓    ↓    ↓
 Service Event Resource
 Registry  Bus  Registry
```

外围再由：

```text
PluginManager
PluginLoader
PluginLifecycleManager
PluginClassLoader
VersionManager
```

负责运行时管理。

------

# 62. 一个必须保留的架构边界

最后给这个项目定一条硬规则：

> **Plugin 可以扩展 Core，但不能反向成为 Core。**

也就是：

```text
                 Core
                  ▲
                  │
            Platform API
                  ▲
                  │
                Plugin
```

而不能变成：

```text
Core
 ▲ ▲ ▲
 │ │ │
Plugin A
Plugin B
Plugin C
```

让 Core 开始依赖具体 Plugin。

一旦出现：

```java
if (plugin instanceof StripePlugin) {
    ...
}
```

或者：

```java
core → stripe-plugin
```

整个 Plugin Architecture 就已经开始腐化。

------

# 63. 一句话定义

最终可以把这个项目定义为：

> **LPR 是一个面向可信 Java 扩展模块的、单 JVM、动态加载、ClassLoader 隔离、Service Registry、Event Bus、生命周期管理和版本切换的 In-Process Plugin Runtime。**

它不是 OSGi 的替代品。

它是：

> **从 OSGi 的复杂能力中提取出 Platform 实际需要的最小闭环。**