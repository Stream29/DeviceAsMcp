# Task Tree

- [done] 构建 DeviceAsMcp
  - [done] 冻结架构与协议路线
    - [done] [确定核心传输协议](../done/2026-08-13-decide-core-transport-contracts.md)
    - [done] [确定跨实例设备操作投递](../done/2026-08-13-decide-cross-instance-operation-dispatch.md)
    - [done] [确定 remote MCP 与文件传输边界](../done/2026-08-13-decide-remote-mcp-contract.md)
  - [done] 搭建多模块工程与开发环境
    - [done] 建立 shared、server、daemon、web 模块
    - [done] 配置 Kotlin、Ktor、serialization 与 Compose
    - [done] 添加 PostgreSQL、Redis、RabbitMQ Compose
    - [done] 建立统一检查与测试入口
  - [done] 实现共享协议与领域核心
    - [done] 定义 operation 与 RPC 协议
    - [done] 定义终端与文件传输模型
    - [done] 定义 MCP 工具 schema
    - [done] 实现超时、去重和状态核心
  - [done] 实现 JVM server
    - [done] 实现账号密码和 GitHub 登录基础
    - [done] 实现设备登记、SSE 与结果回传
    - [done] 实现 Redis owner 与结果路由
    - [done] 实现 RabbitMQ 跨实例 RPC
    - [done] 实现 remote MCP 工具分发
    - [done] 实现文件传输协调与内容 relay
    - [done] 收紧 wire schema 与 MCP 请求校验
    - [done] 完成文件跳过、取消和故障收敛
    - [done] 补齐会话期限与跨域管理请求
  - [done] 实现 Kotlin/Native daemon
    - [done] 实现配置、登录与设备连接
    - [done] 实现 operation 执行与结果重发
    - [done] 完成终端缓存与两秒快路径
    - [done] 完成 POSIX PTY 与 Windows ConPTY
    - [done] 完成文件协商、跳过与状态清理
    - [done] 加固本地凭据与断线故障报告
  - [done] 实现 Compose Wasm 管理面板
    - [done] 实现登录界面
    - [done] 实现设备与 auth key 面板
    - [done] 实现 daemon 安装引导
    - [done] 完成 OAuth 回跳与生产地址配置
    - [done] 完成跨域登出和面板错误处理
  - [done] 完成验证与交付
    - [done] 运行共享和 JVM 测试
    - [done] 运行 PostgreSQL、Redis、RabbitMQ 联调
    - [done] 运行 daemon 到 MCP 端到端验证
    - [done] 编译 Native 与 Wasm 目标
    - [done] 验证 Docker Compose 配置
- [done] 检查 IDEA 状态并记录不适用原因
    - [done] 更新 README 和运行说明
    - [done] 清理所有活动任务

# Details

- 用户已授权持续细化并执行，直到当前项目任务清空。
- 使用纵向可验证切片推进，不把 wire schema、Redis key 命名或框架惯例当作前置阻塞。
- 最终检查时 IntelliJ IDEA 仅打开 `BuildKodex`，因此本仓库未运行
  IDEA 项目级构建或检查；本仓库已通过原生 Gradle、Compose 和 diff 检查。
- Linux x64、macOS ARM64 和 Windows x64 daemon 均通过真实运行时验证。
- Linux ARM64 完成 Release 交叉编译；Wasm 完成生产优化构建。
- PostgreSQL、Redis、RabbitMQ、跨实例 RPC 和固定实例文件 relay 已联调。
- 不提交 Git commit。
