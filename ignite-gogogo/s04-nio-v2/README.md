# s04-nio-v2 · NIO 引擎 v2(多 worker + 过滤链)

从零手搓 Ignite · **S4**:多 selector worker + 轮询 Balancer + 双向过滤链(镜像 `vendors/ignite` 的 `internal/util/nio/`)。从 `s03-nio-engine` 复制扩展。

- 执行规格:`specs/sessions/S04-nio-v2.md`
- 驱动分析:`specs/phases/P01-nio-analysis.md`

## 构建 / 测试
```bash
cd ignite-gogogo/s04-nio-v2
mvn -q test
```

## 结构(相对 s03 新增/改动)
- `Filter` / `FilterChain` / `HeadFilter` / `TailFilter` —— 双向过滤链(镜像 `GridNioFilter`/`GridNioFilterChain`)
- `CodecFilter`(镜像 `GridNioCodecFilter`)/ `LogFilter`(可插拔演示)
- `ClientWorker` —— selector worker(镜像 `AbstractNioClientWorker`),每会话串行无锁
- `NioServer` v2 —— AcceptWorker + N×ClientWorker + 轮询 Balancer(镜像 `GridNioServer` + `offerBalanced`)
