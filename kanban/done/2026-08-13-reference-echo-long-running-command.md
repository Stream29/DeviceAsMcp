# Task Tree

- [done] 参考 Echo 的长程命令行设计
  - [done] 定位 Echo 的命令与终端实现
  - [done] 核对命令执行和输出回传生命周期
  - [done] 核对 tmux 会话的持久与重连机制
  - [done] 对比 DeviceAsMcp 当前终端语义
  - [done] 给出可复用边界和待选项
  - [done] 确认长程命令行的目标语义

# Details

- 只读参考 `/home/stream/ACodeSpace/push/BuildEcho/echo`。
- 用户希望补充长程命令行能力。
- Echo 将后台长命令与可重连交互终端分成两套机制。
- 研究记录见 `../../shared-context/findings/echo-long-running-command.md`。
- 用户确认采用 Echo 式两秒快路径：两秒内完成时直接返回结果，否则返回 session ID，后续通过工具轮询增量输出。
- 不要求通过 tmux 跨 daemon 重启保留会话。
