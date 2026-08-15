# Task Tree

- [done] Release and deploy DeviceAsMcp v0.1.4
  - [done] Confirm the release contract
  - [done] Revalidate the completed snapshot
    - [done] Verify the tracked task and architecture record
    - [done] Run source, test, and credential checks
  - [done] Publish the GitHub snapshot
    - [done] Commit exactly as `feat: snapshot`
    - [done] Push the signed commit to `main`
    - [done] Create and push annotated tag `v0.1.4`
    - [done] Wait for the release workflow
    - [done] Download and verify every release asset
  - [done] Deploy Mac production
    - [done] Verify the current production baseline
    - [done] Back up PostgreSQL before replacement
    - [done] Update the remote repository to the release snapshot
    - [done] Rebuild and recreate server and web
  - [done] Run production acceptance
    - [done] Check public health and semantic routes
    - [done] Verify authenticated device-list invalidation
    - [done] Verify cross-instance messaging readiness
    - [done] Confirm clean logs and test-data state
  - [done] Finalize release records

# Details

- Commit the completed snapshot with the exact subject `feat: snapshot`.
- Publish the next patch release as `v0.1.4`.
- Deploy the same snapshot to the Mac production environment.
- Keep existing PostgreSQL, Redis, RabbitMQ, Caddy, and ngrok data and configuration.
- Do not expose credentials in command output, logs, or release records.
- The pre-deployment PostgreSQL backup is
  `/Users/stream/Library/Application Support/DeviceAsMcp/backups/device-as-mcp-20260815T103600Z.dump`.
- Its SHA-256 is
  `c4a4d15e8bb9ea19659661e8ad07aaa9a0426e75bd38ac9fd5d6ead4b0c79f29`.
- Production emitted exactly four `device_list.update` events with `{}` data for
  initial synchronization, enrollment, rename, and revoke.
- The revoked production device credential returned HTTP `401`.
- The production RabbitMQ event exchange is durable and its single live server
  queue is non-durable, exclusive, and auto-delete.
