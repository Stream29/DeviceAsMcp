# Echo Long-Running Command Findings

- Reference repository: `/home/stream/ACodeSpace/push/BuildEcho/echo`.
- Echo separates non-interactive background commands from interactive durable terminals.

## Background Commands

- The backend allocates a Redis process slot before dispatching a command to a device.
  - See `apps/backend/src/engine/interaction/handlers/bash_handler.ts:527`.
- A command that has not finished after two seconds returns a session ID for later output reads.
  - See `apps/backend/src/engine/interaction/handlers/bash_handler.ts:617`.
- `read_stdout` waits for up to 30 seconds and consumes the currently buffered output incrementally.
  - See `apps/backend/src/services/llm/runtime_tools.ts:7`.
  - See `apps/backend/src/engine/interaction/handlers/read_stdout_handler.ts:34`.
- Redis stores command state and a consumable output buffer with a 30-minute sliding TTL.
  - See `apps/backend/src/services/process_transporter/index.ts:74`.
  - See `apps/backend/src/services/process_transporter/lua.ts:71`.
- The device batches stdout and stderr for one second before reporting them.
  - See `packages/network-core/src/device_command_reporting.ts:103`.
- A report failure stops the local command, so this path does not preserve commands across device-server disconnections.
  - See `packages/network-core/src/device_command_reporting.ts:53`.
  - See `packages/network-core/src/device_command_reporting.ts:120`.
- The local command runner is process-memory-owned, ignores stdin, and uses stdout and stderr pipes.
  - See `packages/entities/modules/exec/node_command_runner.ts:30`.
  - See `packages/entities/modules/exec/node_command_runner.ts:43`.
- The backend merges stdout and stderr when receiving a report.
  - See `apps/backend/src/ws/routes/rpc/device_command_report_output.ts:15`.

## Interactive Durable Terminals

- Echo creates detached tmux sessions and attaches each remote connection through a separate PTY client.
  - See `packages/pty-core/src/internal/tmux_exec.ts:153`.
  - See `packages/pty-core/src/tmux_pty_client.ts:86`.
- Closing an attachment leaves the tmux server and session alive.
  - See `packages/pty-core/src/tmux_pty_client.ts:159`.
  - See `packages/pty-core/src/tmux_pty_client.ts:393`.
- Deleting a session is a separate explicit operation.
  - See `packages/pty-core/src/tmux_pty_client.ts:171`.
- A relay carries sequenced input, resize, output, completion, and error frames.
  - See `packages/entities/entities/ws/socket/tmux_session.ts:88`.
- Reconnection uses the stable tmux session name and creates a new attachment.
  - See `apps/cli/src/runtime/cli_tmux_runtime.ts:41`.
- Windows support depends on an externally installed MSYS2-compatible tmux rather than native ConPTY persistence.
  - See `packages/pty-core/src/tmux_binary.ts:121`.
  - See `packages/pty-core/src/tmux_binary.ts:157`.

## DeviceAsMcp Implications

- Reuse the separation between command lifetime and individual output-read calls.
- Reuse bounded long-polling for incremental command output.
- Do not copy Echo's stop-on-report-error behavior because DeviceAsMcp requires local processes to survive daemon-server connection loss.
- Do not copy Redis destructive reads directly if output retries or multiple readers must be supported.
- Treat tmux-style daemon-restart survival as a separate capability from ordinary long-running background commands.
- Keep native POSIX PTY and ConPTY support separate from an optional external session supervisor.
