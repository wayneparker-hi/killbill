# 设计

- 沿用现有验收工具的精确建库/清库；双节点类独立建库，默认 Surefire 不自动运行 IT。
- 子 JVM 显式设置既有 org.killbill.queue.creator.name；生产 POLLING 模式，claimed=1、queue.capacity=1、单 worker，仅用于限制测试预取。节点日志独立，子 JVM 时区与 Surefire 父进程一致（GMT），避免无时区 SQL 字段在父子进程中的解释不同。
- 发布一批固定 UUID 后，两节点先报告就绪，经文件屏障启动队列。每个 handler 提交 UUID 唯一业务结果并记录节点/UUID，首个处理停在确认前屏障；足够的 backlog 让两节点均能进入 handler，不将任意分配比例作为契约。
- 正常场景使用较长 claim，观察两节点在途且 owner 不同、租约有效，再释放屏障；每条消息恰有一份 PROCESSED、无 REAPED/FAILED，投递记录各一次。
- 故障场景使用短 claim；观察两个节点均在途且租约有效，强制终止其中一个 owned 子进程，存活节点释放屏障后继续运行。已提交但未确认的 UUID 经真实 reaper 重放，最终业务效果一份，投递至少两次且跨节点，REAPED 旧 owner、PROCESSED 新 owner。
- 文件仅协调测试，不替换消息存储、claim 或 ACK。所有等待有截止时间，异常关闭本次 owned 子进程，不停止共享容器、不修改队列记录。
