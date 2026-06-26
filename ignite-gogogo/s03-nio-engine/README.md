# s03-nio-engine · NIO 引擎 v1

从零手搓 Ignite · **S3**:单 selector worker + 会话 + 长度前缀帧(镜像 `vendors/ignite` 的 `internal/util/nio/`)。

- 教学文档:`specs/sessions/S03-nio-engine.md`
- 驱动分析:`specs/phases/P01-nio-analysis.md`

## 构建 / 测试
```bash
cd ignite-gogogo/s03-nio-engine
mvn -q test
```

## 结构
- `core/` —— 对照 Ignite 的 `modules/core`
  - `internal/util/nio/FrameCodec` —— 长度前缀帧(镜像 `GridBufferedParser` / `GridNioServerBuffer`)
  - `internal/util/nio/NioServer` —— 单 selector worker(镜像 `GridNioServer` v1 切片)
  - `internal/util/nio/NioSession` —— 会话(镜像 `GridNioSession` / `GridSelectorNioSessionImpl`)
  - `internal/util/nio/NioServerListener` —— 监听器(镜像 `GridNioServerListener`)
