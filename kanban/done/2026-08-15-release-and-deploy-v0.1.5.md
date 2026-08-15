# Task Tree

- [done] Release and deploy v0.1.5
  - [done] Apply the CMS collector decision
  - [done] Revalidate the complete feature snapshot
    - [done] Verify device descriptions across storage, API, MCP, and UI
    - [done] Verify low-memory Native settings on every target
    - [done] Keep local server and middleware stopped
  - [done] Back up Mac production PostgreSQL
  - [done] Deploy the server and management frontend
  - [done] Run authenticated production acceptance
    - [done] Edit and observe a device description
    - [done] Edit and list a description through MCP
    - [done] Verify health, authentication, and cleanup
  - [done] Commit the signed snapshot
  - [done] Publish GitHub release v0.1.5
  - [done] Verify every release asset
  - [done] Confirm clean local and production state

# Details

- Use the Kotlin/Native CMS collector on every daemon target.
- Release version is `v0.1.5`.
- The required commit subject is exactly `feat: snapshot`.
- Do not start the server, PostgreSQL, Redis, or RabbitMQ on the local
  workstation.
- Back up production before applying the PostgreSQL description migration.
- Deploy to the existing MacBook ngrok production environment.
- Do not expose credentials in commands, logs, artifacts, or the final report.
- The release, deployment, rollback, and acceptance route is fully determined.
- Execute validation before committing or tagging the snapshot.
- The pre-deployment PostgreSQL backup is
  `/Users/stream/Library/Application Support/DeviceAsMcp/backups/device-as-mcp-20260815T131850Z.dump`.
- Its SHA-256 is
  `5974b66c9e26388a0fa4c4bbfd49f828565cef5e4b305124253b232f6ec9b7bb`.
- The production server and web images built successfully on the MacBook.
- Production acceptance verified description preservation through the
  management API, modern MCP, and legacy Codex-compatible MCP.
- Production emitted exactly five `device_list.update` events with `{}` data
  for initial synchronization, three description updates, and device
  revocation.
- Revoked device and access-key credentials both returned HTTP `401`.
- The production description column is present and non-null.
- The authenticated acceptance user and its dependent records were removed.
