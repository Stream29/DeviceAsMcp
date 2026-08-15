# Task Tree

- [done] Fix Web JSON compatibility and release v0.1.6
  - [done] Confirm the stale-client failure
    - [done] Reproduce the error in the open browser page
    - [done] Isolate the strict frontend response decoder
  - [done] Implement tolerant Web response decoding
    - [done] Keep the shared protocol decoder strict
    - [done] Add an unknown-field regression test
  - [done] Validate the completed snapshot
    - [done] Run Web tests and production compilation
    - [done] Run relevant project verification
    - [done] Keep local server and middleware stopped
  - [done] Deploy the Mac production frontend
    - [done] Back up PostgreSQL before deployment
    - [done] Rebuild and recreate the Web service
    - [done] Verify the authenticated devices page
  - [done] Commit the signed snapshot
  - [done] Publish GitHub release v0.1.6
  - [done] Verify every release asset
  - [done] Confirm clean local and production state

# Details

- Release the fix as `v0.1.6`.
- Use a Web-specific copy of `ProtocolJson` with `ignoreUnknownKeys = true`.
- Keep `ProtocolJson` strict for server, daemon, storage, and MCP validation.
- Cover the original failure mode by decoding a previous response model from
  JSON containing a newly added field.
- Use the exact commit subject `feat: snapshot`.
- Deploy only the changed Web service while preserving the running backend and
  middleware.
- Do not start the server, PostgreSQL, Redis, or RabbitMQ on the local
  workstation.
- The pre-deployment PostgreSQL backup is
  `/Users/stream/Library/Application Support/DeviceAsMcp/backups/device-as-mcp-20260815T173139Z.dump`.
- Its SHA-256 is
  `eabf5bc9a8b3a4ccaf1502eb12d6c5e1fce647d7919175305251728d2fbc1a4f`.
- The production Web build emitted application Wasm
  `4f5df298995af30c3203.wasm`.
- The authenticated devices page loaded all three devices without an
  application error after the new Web image was deployed.
- All three Web tests passed, including the stale-client regression test.
- The Web production distribution and shared JVM tests passed locally.
- The release workflow now runs the Web tests before publishing.
