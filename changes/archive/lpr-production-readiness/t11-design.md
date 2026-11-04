# T11 生命周期故障隔离

- Core 新增停止执行器 SPI 入参，默认实现位于独立 `lpr-lifecycle-default` adapter；不在 Core 创建线程池。
- 新增平台配置 `org.killbill.billing.plugin.lifecycle.stopTimeout`（30,000ms）及 `stopMaxThreads`（8）。TestKit 支持缩短期限。
- 停止服务视图先关闭注册能力，再撤销已注册服务；异步执行 stop 和清理，宿主最多等待 stopTimeout。
- 不合作 stop 保持 STOPPING，占用一个有界 worker；禁止同版本重载/卸载，发现时保留实例。回调实际结束后才关闭 ClassLoader、清除引用并进入 STOPPED。
- 执行器无排队，饱和时拒绝新停止操作并保留原状态，避免无界积压；关闭执行器只请求中断，不强杀线程。
- 启动/停止/单个资源释放捕获插件 Error，继续可行的清理；真实 JVM 全局内存耗尽不在进程内存活保证内，进程隔离属于 T10。
- 当前已通过的全量构建（92 项目）和 185 项测试为改动前基线。
- 不移除既有 Guice 绑定：新增独立 adapter singleton，并由现有 manager provider 注入；宿主 stop 在 manager.stopAll 后关闭 adapter。
