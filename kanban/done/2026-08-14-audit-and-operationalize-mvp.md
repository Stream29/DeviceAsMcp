# Task Tree

- [done] 验收审计并完善部署运维
  - [done] 完成基线与缺口审计
  - [done] 修复跨实例 daemon 结果归还
    - [done] 通过连接 owner 路由随机落点结果
    - [done] 由连接 owner 转发 operation origin
    - [done] 增加 RabbitMQ 跨实例集成验证
  - [done] 修复 manifest 独立传输边界
    - [done] 从 operation 预检结果移除 manifest
    - [done] 等待并校验 relay manifest
    - [done] 增加协议与 relay 回归测试
  - [done] 建立生产配置与健康检查
    - [done] 强制生产环境使用全部中间件
    - [done] 增加 liveness 和 readiness
    - [done] 增加配置与健康检查测试
  - [done] 加固容器与生产部署
    - [done] 使用非 root server 镜像
    - [done] 构建静态前端与 Caddy 网关镜像
    - [done] 提供受控单实例生产 Compose
  - [done] 完善运维说明与持久记录
    - [done] 记录审计结论和架构修正
    - [done] 说明启动、升级、备份和恢复
  - [done] 执行完整验收
    - [done] 运行单元与中间件集成测试
    - [done] 验证镜像和生产 Compose
    - [done] 检查格式、diff 和临时资源

# Details

- 用户已授权继续执行验收审计和部署运维。
- 先依据现有实现确认具体缺口，不预设或扩展产品功能。
- 基线 JVM、Linux Native 和 Wasm 构建检查通过。
- 审计时发现 daemon 结果落到非连接 owner 实例会被拒绝，文件
  manifest 仍经过 operation 结果和 RabbitMQ。
- daemon 结果现可从任意入口经连接 owner 转发到 operation origin，
  并已通过真实 Redis 和 RabbitMQ 三实例测试。
- 文件预检结果已不再包含 manifest；manifest 只走 relay HTTP，目标
  端会等待并校验来源端独立上传的内容。
- `production` 模式会强制 PostgreSQL、Redis、RabbitMQ 和前后端 HTTPS；
  liveness 与三项中间件 readiness 已分别暴露并通过测试。
- 后端非 root 镜像、Compose Wasm/Caddy 镜像和受控单实例生产
  Compose 已完成实际构建。
- 隔离生产栈已验证 HTTPS、静态资源、readiness、登录、只读后端容器
  和 PostgreSQL 备份，并已清理全部临时容器、卷、标签和文件。
- 运维手册和验收审计结果已分别记录在 `ops/production.md` 与
  `shared-context/findings/mvp-acceptance-audit.md`。
- 完整 JVM、Linux x64 Native、Wasm、真实中间件、镜像和生产 Compose
  验收均已通过；macOS ARM64 与 Windows x64 仍按平台发布要求在对应
  主机验收。
- 修复范围保持在审计发现的正确性问题、必要回归测试和生产运行基线。
