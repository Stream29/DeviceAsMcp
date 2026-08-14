# Task Tree

- [done] 确定设备操作投递协议
  - [done] 确定 SSE 事件信封
  - [done] 确定结果 POST 信封
  - [done] 确定操作取消语义
  - [done] 更新核心传输任务
  - [done] 更新顶层实施任务树
  - [done] 记录确认后的项目决策

# Details

- 作为 `2026-08-13-decide-core-transport-contracts.md` 的子任务。
- 用户级实例亲和已降级为优化；跨实例时采用本地直发加 RabbitMQ RPC fallback。继续沿用传输级确认、10 秒超时和一次重传设计。
- owner 成功校验连接并写入 daemon SSE 后返回 RabbitMQ dispatch RPC response，以此作为跨实例传输投递回执；该 RPC 不等待最终结果。
- dispatch RPC timeout 为 5 秒；超时、连接失败或返回 stale owner 时不重查 owner、不重试，原调用直接失败。
- daemon 最终结果先返回 connection owner，再由 owner 通过独立 RabbitMQ RPC 转发至持有 operation waiter 的 origin 实例。
- connection owner 仅在 origin 接受转发结果或判定重复后确认 daemon；无法转发时返回可重试错误，由 daemon 重发同一结果。
- SSE 使用统一的 `operation` 事件和带版本的 JSON 信封。
- 结果 POST 使用 success/failure 判别联合。
- request-scoped MCP SSE response 被关闭时重新查询当前 device owner，只向当前 owner 发送一次不确认、不重试的尽力取消控制事件。
- 单 JSON MCP 请求断连不视为 MCP 协议取消。
- daemon 只取消尚未开始的 operation；已开始或完成时忽略。
- 已经启动的终端进程不因原 MCP 调用取消而退出。
