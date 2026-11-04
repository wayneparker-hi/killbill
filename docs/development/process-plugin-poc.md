# 进程外插件 PoC

T10 交付的是显式调用的研究适配器 `lpr-process`。它在独立 JDK 21 JVM 中运行 `ProcessPlugin`，经本机 Unix domain socket 调用；默认 Kill Bill 启动、节点命令和所有现有插件继续使用原有运行方式。

## 能力与取舍

| 内容 | PoC 行为 |
|---|---|
| Worker 契约 | `ProcessPlugin.start()`、`invoke(String, List<String>)`，公共无参构造 |
| 消息 | 版本/关联 ID/状态 + 长度前缀 UTF-8 字符串列表；不传 Java 对象 |
| 调用 | 每实例单个在途请求，超额立即拒绝；启动、读写、关闭有截止时间 |
| 失败 | 远端异常显式失败；传输/协议/超时/取消使 client 失效并终止 worker |
| 重试 | 不自动重试或重启；支付发送后的执行结果可能未知 |
| JVM | 独立 heap 上限，OOM 退出；仅必要运行时 classpath，插件私有依赖 child-first |
| 本地权限 | 每实例独占 0700 临时目录与 socket；challenge 经 stdin 交给 worker |
| 输出 | worker stdout/stderr 丢弃，避免输出阻塞和无界日志；无远程诊断采集 |
| 资源 | `resources().manage(client)`；关闭连接、终止进程、删除 socket/目录 |

Unix socket 由 JDK 提供，不引入 gRPC、Netty 或 protobuf。当前面向支持 Unix domain socket 的 macOS/Linux；不自动回退到 TCP。socketRoot 需可写且路径足够短（Unix socket 路径有系统长度限制）；本机测试使用 `/tmp` 下的临时目录。

## 最小使用

Worker JAR 实现：

```java
public final class EchoWorker implements ProcessPlugin {
    @Override
    public List<String> invoke(final String operation, final List<String> arguments) {
        if (!"echo.v1".equals(operation)) {
            throw new IllegalArgumentException("Unsupported operation");
        }
        return arguments;
    }
}
```

宿主侧显式提供 Java 可执行文件、socketRoot 和预算，不从系统属性读取配置：

```java
final var settings = new ProcessPluginSettings(javaExecutable, socketRoot,
        Duration.ofSeconds(5), Duration.ofSeconds(1), Duration.ofSeconds(2),
        4096, 64);
final var client = context.resources().manage(new ProcessPluginLauncher().launch(
        artifact, processDescriptor, settings));
final List<String> result = client.invoke("echo.v1", List.of("hello"));
```

`artifact` 和 `processDescriptor` 的 pluginId/version 必须一致；entrypoint 是 `ProcessPlugin`，不是原 `Plugin`。配置值由调用方提供；若未来接入平台装配，必须通过 `PluginRuntimeConfig` 等 skife-config 接口，不要在业务代码读取 System properties。插件私有 library 路径来自 artifact，均只在 worker 的插件 ClassLoader 中加载。

原 `PluginContext`、Core API 回调、EventBus、HTTP servlet、支付完整接口等都没有跨进程透明代理。可以让一个普通 LPR 插件注册业务代理并把 client 交给 ResourceRegistry；研究测试用这一方式验证既有生命周期与版本作用域。

## 支付验证

`TestProcessPaymentAuthorization` 运行真实 fork 与现有 `KillbillPluginServiceBridge` / typed registry。研究代理 `ProcessAuthorizationPoc` 仅支持 `authorizePayment`，其他方法明确返回 `POC_UNSUPPORTED`；它只存在于 test source，不会成为生产网关。

请求包含账户、支付、交易、支付方式、精确十进制金额、币种、租户与 user token。worker 返回这些字段及 worker PID/状态，宿主验证完整结果，再构造宿主 ClassLoader 中的 `PaymentTransactionInfoPlugin`。测试使用合成授权结果，不连接外部支付网关。

发送后超时、崩溃或异常不能证明网关没扣款。`ProcessPluginCallException.isResultUnknown()` 提醒调用方核对副作用；研究支付代理映射成 `POC_RESULT_UNKNOWN`。不要在异常处理里自动换新进程再次扣款。正式落地需要网关幂等键、交易状态查询/对账和错误分类。

## 执行测试

完成全量构建安装依赖后，运行：

```bash
mvn -pl lpr/lpr-process test
mvn -pl killbill-platform/plugin-runtime test -Dtest=TestProcessPaymentAuthorization
```

使用 JDK 21 与本机真实 Maven 路径；通用开关及 surefireArgLine 见 [构建环境](build-environment.md)。CI 的全量构建和 runtime 回归已包含新模块及支付研究测试，无外部 DB 或网关要求。

## 故障边界与后续

- `Runtime.halt`、真实子 JVM 堆耗尽、假死和启动卡住已通过 fork 测试；只证明此故障边界，不承诺整机资源耗尽下宿主存活。
- worker 监测父进程死亡，即使插件启动或调用不返回也退出。父进程骤停后私有 socket 目录可能遗留，需运维清理；正常关闭/启动失败会清理。
- JVM 进程隔离不是 OS 沙箱。插件仍是可信代码；不抵御同 UID 攻击、任意文件/网络访问或逃逸的子孙进程。正式隔离需独立 UID、cgroup/container、最小环境和权限。
- 没有自动 worker 重启、健康路由、进程外热升级协调、反向 Core 调用、完整支付序列化、兼容性协商或生产认证授权协议。当前协议版本固定为 1，不兼容即拒绝。
- 并发上限是每实例 1，PoC 不提供全局实例数量配额；宿主必须控制创建数量。关闭尽力正常终止，预算后强杀；不能在预算内确认退出会抛 IOException。
- 后续先决定哪类支付操作需要进程隔离，再设计完整数据契约和幂等/对账；满足实际并发和运维需求后评估 gRPC 与独立 supervisor，避免直接把 PoC 当成完整生产网关。
