# MVP Acceptance Audit

- Audited on 2026-08-14.
- JVM tests, Linux x64 shared tests, Linux x64 daemon compilation, and the Wasm
  production distribution pass on the Linux development host.
- Real PostgreSQL, Redis, and RabbitMQ integration tests pass.
- A daemon result may enter any server instance. The ingress resolves the
  current daemon owner, the owner validates the connection, and the result is
  forwarded to the operation origin.
- File manifests no longer use operation results or RabbitMQ. They use the
  independent relay HTTP request with the 16 MiB request limit.
- Production mode rejects missing PostgreSQL, Redis, RabbitMQ, or HTTPS
  configuration.
- Liveness is independent from readiness. Readiness checks all three external
  middleware connections.
- The server image runs as a non-root user.
- The web image builds the Compose Wasm distribution and serves it through
  Caddy with SSE buffering disabled.
- The provided production Compose topology intentionally runs one server
  instance. Multi-instance deployment still requires exact relay-instance
  routing at the gateway.
- PostgreSQL is the durable backup target. Redis and RabbitMQ only contain
  replaceable routing, transfer, lease, and in-flight control state.
- macOS ARM64 and Windows x64 Native binaries still require verification on
  matching hosts before publishing those platform artifacts.
