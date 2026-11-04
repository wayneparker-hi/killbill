# T7 Service Registry 接口版本协商

## 契约

- 接口版本与插件发布版本独立；新增 `lpr-api.ServiceApiVersion`（仅 JDK 类型），使用已发布的 MAJOR.MINOR.PATCH，不接受预发布/构建标签或不规范数字。
- 注册属性 `service.api.version` 为版本字符串；旧注册默认 `1.0.0`。API 提供类型化注册便捷方法，reference 暴露不可变版本元数据；无效声明在写入 registry / 通知 listener 前拒绝。
- 兼容方向是 provider >= consumer 所需版本且 major 相同；major 0 仅精确匹配。该声明是接口语义契约，不代替 ABI 门禁或证明插件实现正确。
- Core 及插件可显式按所需版本 lookup；无版本 lookup 使用宿主对该类型的要求，默认 1.0.0。过滤后保留 priority 排序、健康/选定版本/暂存隔离、版本作用域撤销规则；不自动越过已选插件版本寻找旧版本。
- `publishedServices` 仍包含不兼容注册用于诊断；不兼容 provider 不出现在普通 getService/getServices/getPluginIds 中。

## 平台集成

- skife-config `org.killbill.billing.plugin.services.api.requiredVersions`，逗号分隔 `接口全限定名=MAJOR.MINOR.PATCH`，默认空。解析为不可变 map，重复/无效声明启动失败。
- registry 与 Kill Bill bridge 使用同一宿主要求；bridge 按实际消费目标类型检查（包括 subtype 路由），检查在代理创建与写入 typed registry 前执行。
- 普通、单值及 healthcheck 桥接均检查兼容性；拒绝时 warn 并跳过，保留既有插件失败容错语义。不兼容注册不进入路由历史，不会在撤销/刷新时复活。
- 没有服务的安装/启动不因此自动失败；版本协商控制服务可达性，不替代升级健康检查。已取得的引用仍遵循现有不得跨停止/升级缓存的约束。
- 既有 bind / provider 保留：registry provider 注入版本要求，manager provider 把同一要求传给 bridge；新增解析/策略 provider，无具体插件依赖。

## 验证

基线 `e64aeac` 全量构建；版本解析/兼容方向/0.x、缺省迁移、不可变元数据、无效注册不产生 listener/指标副作用、显式 lookup、scope 关闭与版本撤销、排序/健康/暂存过滤、普通/单值/healthcheck/subtype 桥接、配置装配与真实 ClassLoader 端到端。新增守卫逐项变异确认失败并还原；全量构建 + ABI + 隔离/卸载/TestKit 及既有目标回归。T8/T10 保留待办。
