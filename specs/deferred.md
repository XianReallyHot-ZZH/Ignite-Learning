# 延后与未做功能清单(Deferred / Out-of-Scope Backlog)

> Ignite 有、但学习版简化/跳过的功能。两类:
> - **→S??**(情况 A):延后到某下游 session(roadmap 已跟踪,这里交叉引用确认下游真捡了)
> - **out-of-scope**(情况 B):无 session 会做 —— 真正的 backlog;35 session 完成后的"进阶深入"菜单
>
> **增量维护**:session-code 写讲义「与 Ignite 对照」时,凡标"不做/选做"且无下游 session → 加一行到本文件。
> **不做硬门**(哪些算"跳过"是主观的);就是约定 + 活文档。

---

## Phase 1(NIO)

### 情况 A:延后到下游 session(roadmap 跟踪)

| 功能 | 去向 | 出处 |
|---|---|---|
| handshake / 重连编排(交换 rcvCnt、resend 触发) | → S20(Communication) | S05 讲义 对照 |
| recovery descriptor 消费者侧拥有(接入 session meta、handshake wiring) | → S20 | S05 讲义 对照 |

### 情况 B:out-of-scope(无 session,backlog)

| 功能 | 为什么简化/跳过 | 出处 | 深入优先级 |
|---|---|---|---|
| `SelectedSelectionKeySet` 反射优化 | 性能:反射替换 JDK Selector 内部 set 为数组,消除 HashSet 迭代分配;学习版不需要 | S03 讲义 对照 | 低 |
| DirectByteBuffer(堆外) | 性能:减少 GC;学习版用 heap `ByteBuffer` | S03 讲义 对照 | 低 |
| MOVE 跨 worker 迁移(`SizeBasedBalancer`) | 功能:按字节量迁移会话做负载均衡 | S04/S05 讲义 对照 | 中 |
| `GridNioAsyncNotifyFilter`(listener offload 线程池) | 功能:避免 listener 回调阻塞 NIO 线程 | S04 讲义 陷阱 | 中 |
| slow-client 策略(消费者侧踢慢节点) | 功能:生产稳定性 | S05 讲义 对照 | 中 |
| SSL/TLS 完整实现(`SSLEngine` 握手 + unwrap/wrap) | 功能:加密通信;学习版只留 `SslFilter` 占位 | S05 讲义 对照 | 高 |

---

## Phase 2(Direct + Marshaller)

### 情况 B:out-of-scope(无 session,backlog)

| 功能 | 为什么简化/跳过 | 出处 | 深入优先级 |
|---|---|---|---|
| codegen `MessageSerializer` + `@Order` 注解处理器 | Ignite 2.18.0 现代写法:编译期生成等价的 `switch(state)` 状态机,消除手写样板;v1 手写 `writeTo`/`readFrom` | S06 讲义 对照 | 中 |
| varint+zigzag 编码 int/long | 性能:base-128 varint 让小数值(数组长度/ordinal/timeout)省字节;v1 用定宽 | S06 讲义 对照 | 中 |
| 跨 partial-read 的 resume 状态机 | 性能:`DirectByteBufferStream` 的逐字段游标(`tmpArrOff`/`arrOff`/`uuidState`/`prim`…)跨 NIO 读边界续读,零中间缓冲;v1 由 `FrameCodec` 保证消息完整,不需要 | S06 讲义 对照 | 高 |
| `CacheObject`/`KeyCacheObject`/`AffinityTopologyVersion` Direct 字段类型 | 保真:Ignite `internal/direct` 向上耦合 cache 子系统(一等公民字段类型);学习版载荷走 Marshaller `byte[]`,不需要 | S06 讲义 对照 | 低 |
| `Collection`/`Map` Direct 字段 | 功能:v1 只原语数组;后续协议消息若真有集合字段再补 | S06 讲义 对照 | 低 |
| `MessageFormatter` SPI | 功能:多 reader/writer 切换(插件扩展点);v1 单一实现 | S06 讲义 对照 | 低 |
| Ignite 的 `writeHeader`/`isHeaderWritten` 机制 | 简化:Ignite 消息自带 type header;v1 把 type 统一交给上层(codec 顶层 / writeMessage 嵌套)写,去掉这层历史包袱 | S06 讲义 对照 | 低 |

---

> **后续 phase 逐个追加**(Phase 2~14),格式同上。35 session 完成后,out-of-scope 列 = "进阶深入"菜单。
