# s05-nio-v3 · NIO 引擎 v3(recovery + 双重背压 + SSL 槽)

从零手搓 Ignite · **S5**(Phase 1 收官):recovery(计数器去重 + resend)+ 双重背压(发送信号量 + 接收 `MessageTracker` 暂停/恢复)+ SSL 过滤器占位。从 `s04-nio-v2` 复制扩展。

- 执行规格:`specs/sessions/S05-nio-v3.md`
- 驱动分析:`specs/phases/P01-nio-analysis.md`

## 构建 / 测试
```bash
cd ignite-gogogo/s05-nio-v3
mvn -q test
```

## 结构(相对 s04 新增/改动)
- `RecoveryDescriptor`(镜像 `GridNioRecoveryDescriptor`)—— 计数器 + 有界未确认队列 + `onHandshake`/`resend`
- `BoundedWriteQueue`(镜像 `GridSelectorNioSessionImpl.queue+sem`)—— 发送背压(message-thread 旁路)
- `GridBackPressureControl`(镜像 `GridNioBackPressureControl`)—— ThreadLocal 标记 worker 线程
- `MessageTracker`(镜像 `GridNioMessageTracker`)—— 接收背压(pause/resume OP_READ)
- `SslFilter` —— SSL 占位(直通,不实现真实加密)
- `NioSession`/`HeadFilter`/`ClientWorker` v3 —— 接入上述机制(默认关闭,行为同 v2)
