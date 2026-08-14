# Task Tree

- [done] 确定终端会话的输入输出协议
  - [done] 确定输出轮询等待方式
  - [done] 确定标准输出与错误表示
  - [done] 确定标准输入写入方式
  - [done] 记录确认后的项目决策

# Details

- 延续已确认的两秒后台化、隐式消费和有界缓存设计。
- `terminal_session_output` 立即返回，不进行长轮询。
- 非 TTY 会话分别返回 stdout 和 stderr。
- `terminal_session_input` 接受 UTF-8 `stdin` 和布尔值 `eof`。
