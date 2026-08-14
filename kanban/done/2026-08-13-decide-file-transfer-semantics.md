# Task Tree

- [done] 确定文件传输语义
  - [done] 确定远程路径模型
  - [done] 确定上传覆盖与原子性
  - [done] 确定中断后的处理方式
  - [done] 记录确认后的项目决策

# Details

- 延续已确认的端到端分块中继设计。
- 服务端不持久化文件内容。
- 文件路径要求为设备绝对路径，同时支持以 `~` 表示 daemon OS 用户的主目录。
- 最终目标路径已存在时，在写入任何内容前拒绝整个传输。
- 直接写入最终目标路径，不使用临时路径或原子重命名。
- 首版不支持续传；中断或取消后保留已经写入的部分目标内容。
- 普通文件严格串行传输；单文件流失败后从头重试一次，仍失败则停止整个传输。
- manifest 使用单 JSON 文档，请求体上限为 16 MiB。
- 每个文件尝试使用一个完整原始 HTTP 字节流，并依赖 HTTP/Ktor 背压。
- 独立文件流复用 daemon 设备凭据，并使用流式 SHA-256 与字节数校验源目标内容。
- 跳过目标平台不支持或发生路径冲突的条目，并继续传输其余内容。
- Redis 是 transfer 状态的唯一事实来源；server 进程内不保留第二份状态。
- server 实例故障后，daemon 尽力把现存 running Hash 标记为 failed，不重建缺失 Hash。
- 接受 launch 的实例固定为 coordinator/relay；它通过本地直发或 RabbitMQ RPC fallback 协商两端 owner，文件内容流随后直接连接该实例。
- 常见情况下 coordinator 与两端 owner 是同一实例，不经过 RabbitMQ；RabbitMQ RPC 只承载跨实例控制消息，不承载文件字节。
- daemon 文件内容请求携带 `relayInstanceId`，网关据此精确路由至固定 coordinator；coordinator 不可用时传输失败，不迁移活动 relay。
- 只传输文件内容与目录结构，不保留源文件元数据。
