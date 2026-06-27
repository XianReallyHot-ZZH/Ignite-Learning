# S02 · 执行规格:NIO / 并发热身

> **Phase 0 · 前置基础 · v1** · 无里程碑
> 执行约束规格(瘦)。**Phase 0 不镜像 Ignite 源码**(前置热身)→ §3/§6 标 N/A,cited-paths lint 跳过。
> 代码 `ignite-gogogo/s02-nio-warmup/`(从 s01 复制)。讲义 `docs-learn/S02-nio-warmup.md`。

## 1. 范围与位置
- **roadmap S 块**:Session S2(权威范围/前置/实现要点/验收)。
- **phase**:Phase 0(前置基础)。
- **本 session 做**:单线程 `Selector` echo server + 客户端(热身);复习并发(线程池 / Future)概念。
- **本 session 不做**:多 worker、过滤链、长度前缀帧、pull-based 写、Ignite 镜像(全留 S3+)。
- **前置**:S1(复制骨架)。

## 2. 对外"接口契约"(概念前置)
> Phase 0 无 API 契约;这里列 S3 需要**学习者已掌握**的概念(本 session 是热身,S3 会重写为生产版):

| 概念 | 内容 | 供 S3 |
|---|---|---|
| `Selector` / `Channel` / `ByteBuffer` | NIO 三件套基本用法 | S3 `NioServer` 的根基 |
| `OP_READ` / `OP_WRITE` / `OP_ACCEPT` | interestOps 基本模型 | S3 多 worker + pull-based 写 |
| 单线程 selector 主循环 | `select()` → 遍历 ready keys | S3 把它"产品化" |

## 3. Ignite 源码导读
**N/A** —— 前置热身,不镜像 Ignite。(可读 `vendors/ignite/internal/util/nio/GridNioServer.java` 看"目标形态",**不复现**。)

## 4. 实现步骤(v1)
1. 复制 `s01-skeleton/` → `s02-nio-warmup/`,改 artifactId。
2. `EchoServer`:单线程 `Selector`;`accept`→`register(OP_READ)`;`read`→`channel.write` 回写(echo)。
3. `EchoTest`:起 server,阻塞 `Socket` 客户端发收,断言 echo 往返。
4. (可选)并发小练习:线程池 / `Future`(概念复习,不强制)。

## 5. 验收 = 具名测试

| 验收点 | 测试 |
|---|---|
| echo 往返 | `EchoTest#echoRoundtrip` |

- demo:client 发 `"hello-nio"`,server 原样回。

## 6. 引用路径(lint 核验对象)
**N/A**(Phase 0 不引用 Ignite 源码;cited-paths lint 跳过)。

---
**工时**:⭐⭐ / 2~3 天  **产出物**:单线程 Selector echo(热身,S3 的前置)
