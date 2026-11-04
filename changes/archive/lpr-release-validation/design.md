# 设计

- 用 Docker CLI 调用现有 psql / redis-cli，无额外 Python 包；数据库名由随机 UUID 生成且只在创建成功后纳入本次资源集合。DDL 带 ON_ERROR_STOP，空库事务初始化，清理仅本次库。
- PostgreSQL 兼容域/函数与 serial 转 bigint 事件触发器先建；之后按 DBTestingHelper 的包和 test/main 顺序执行源码 DDL。事件触发器要求现有管理员权限，不修改集群角色。
- 数据库测试和 HTTP 冒烟使用不同的全新数据库，防止测试清表影响重启验收。
- 容器外通过现有映射端口访问 PostgreSQL；旧 DBTestingHelper 固定 localhost:5432，选定测试显式验证这一限制。
- 小型 Jetty 容器 helper 仅加载 reactor 已管理的 Jetty 依赖，部署实际 WAR/web.xml；绑定 127.0.0.1:0，启动完成后回传实际端口。父 Python 进程管理其生命周期、截止时间与退出码。
- 通过既有 skife-config 文件配置实例、插件目录、数据库和队列；不覆盖生产实现，不使用测试 Guice 模块。
- 临时配置位于权限 0700 的输出目录；报告不含密码。成功清理数据库，失败保留其名称和日志供复现；始终停止本次服务，不停止共享容器、不执行 Redis FLUSH。
