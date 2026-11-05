# 设计

- 复用发布验收的独立 PostgreSQL 库、生产 external bus/adapter/DAO/reaper 与现有子 JVM，保持 GMT、英文 locale 和四个 add-opens。
- 每个场景一个固定 UUID。先让 A 取得有效 claim、提交夹具业务效果并进入确认前屏障，再 SIGSTOP 整个 owned JVM，核对 ps 状态 T。其 worker、dispatcher 与 reaper 都暂停，避免把 A 自己回收误当成 B 接管。
- 启动 B，在同库同订阅下自然等待 6s claim / 8s reapThreshold，核验 REAPED 旧 owner 与不同 record_id 的新 IN_PROCESSING、B 的有效 claim。B 此时也停在 handler 屏障，两个 JVM 都存活。
- 保持 B 新记录在途，SIGCONT A 并释放 A 屏障。分别让 A 返回成功或抛普通异常；等待 A 正常 stopQueue 排空，以包含实际 ACK 或重试写入。
- 对比 B 活跃记录的全部查询字段，要求迟到完成/重试不删除、重置或改写 B 的新尝试。之后释放并正常停止 B，验证无 active 消息、无 FAILED、信封身份不变及 UUID 业务效果一份。
- 记录历史按物理尝试/owner 解释；不把可能多条 PROCESSED 直接判为重复业务副作用，也不声称旧 worker 被 fencing。
- 所有等待有界；只对本次 Process 对象的 PID 发送 STOP/CONT，异常 finally 强杀 owned 子进程。共享容器、角色、业务库及队列状态不改动。
