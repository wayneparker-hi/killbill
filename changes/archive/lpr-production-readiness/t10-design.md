# T10：进程外插件 PoC

## 范围与选择

- 独立 `lpr-process` adapter，不修改 Core 状态机，不接入默认 PluginRuntimeModule 或节点命令。平台研究测试经真实 PaymentPluginApi authorizePayment + KillbillPluginServiceBridge/typed registry 调用；其他支付方法明确不支持。
- 选择 JDK 21 Unix domain socket，避免 PoC 引入 gRPC/Netty/protobuf 依赖。支持 macOS/Linux；不降级到 TCP。API 契约仅字符串 / 不可变字符串列表，业务 schema 留在平台适配层。
- 新 `ProcessPlugin` 契约用于 worker，不等同于原 Plugin/PluginContext；worker 无 Core 服务反向调用、持久化事件或完整插件生态兼容承诺。
- 宿主先创建 private 0700 目录并监听 socket，再启动受限 heap 的独立 JVM。worker 经现有 child-first factory 加载插件 JAR，start 完成才连接发送带身份/版本/随机 challenge 的握手。主进程在启动截止时间校验。
- 子 JVM classpath 只含 process/API/SPI/classloader/日志门面，不继承宿主 classpath；插件 JAR 和私有 lib 仅进入插件 loader。challenge 经 stdin 传输，不放命令行。移除可覆盖 JVM 限制的 JAVA_TOOL_OPTIONS/JDK_JAVA_OPTIONS/_JAVA_OPTIONS/CLASSPATH；stdout/stderr 重定向 discard，避免插件输出阻塞或无界日志文件。

## 协议与并发

长度前缀二进制帧：版本、requestId、operation、字符串列表；响应含相同 requestId 与明确成功/远端失败状态。不使用 ObjectInputStream/任意类型反序列化。验证计数、各字段及总帧 UTF-8 字节上限，写入前预检，接收按上限分配。

每 client 非阻塞 tryLock 准入，最多一个在途；NIO selector 按 System.nanoTime 统一截止时间覆盖 connect/read/write，不引入每请求线程/计时器。取消保留中断标志，通道失效后杀进程。远端业务异常是显式失败，通道可继续；协议/IO/超时则永久失效，必须由管理者显式新建实例。

支付一旦可能发出，断连/超时可能发生在网关执行之后，结果必须标为未知；不自动重试、重启或伪造成功。PoC 不实现幂等账本/支付结果对账；业务交易 UUID 用于后续幂等核对。

## 资源与边界

client 由 PluginContext ResourceRegistry 管理；停止按既有服务撤销 → Plugin.stop → resource close 顺序。close 关闭 socket 打断在途 IO，再正常 destroy，预算内未退出则 destroyForcibly；有界等待确认子 JVM 消失并清理目录/socket，无法清理时显式报告失败。

父 socket EOF 或 ProcessHandle.onExit 检测父进程死亡触发 worker 退出（包含 start 卡死之前注册）；父进程骤停遗留私有目录由运维清理；插件 start/close 卡死由父进程终止。JVM 进程不是 OS 沙箱，不覆盖 fork 后逃逸的子孙进程、同 UID 恶意代码或整机内存耗尽。无网络发布/真实支付网关。

## 验证

基线为 T8 `18dcc6d`；先全量构建与现有隔离验证。新增真实 fork PID、私有类不进宿主、帧边界/协议负例、崩溃/假死/启动失败、取消/并发准入、关闭/临时目录清理，以及平台支付授权、租户字段、ClassLoader/版本作用域测试。新增守卫逐项临时破坏确认失败并还原；最终全量构建/ABI/既有回归 + CI 新模块测试。
