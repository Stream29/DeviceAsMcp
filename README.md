# DeviceAsMcp

Expose a user's devices as an authenticated remote MCP endpoint.

## Modules

- `shared`: serialized operations, RPC, terminal, file-transfer, and MCP contracts.
- `server`: JVM Ktor server and OAuth authorization server.
- `daemon`: Kotlin/Native device agent.
- `web`: Compose Multiplatform Wasm management panel.

The daemon targets:

- Linux x64.
- Linux ARM64.
- macOS ARM64.
- Windows x64.

## Architecture

- A daemon maintains one outbound SSE connection to a server instance.
- Operation results return through ordinary authenticated HTTP requests.
- Redis stores device owners, operation routes, and file-transfer state.
- RabbitMQ provides exact-instance control-plane RPC.
- PostgreSQL stores users, sessions, devices, OAuth tokens, and auth keys.
- File contents use independent HTTP streams through one fixed relay instance.
- Server instances generate a new UUID `instanceId` on every process start.
- Gateway affinity by user is optional and only improves the local fast path.

## Development

Requirements:

- JDK 21.
- Docker with Compose.
- A matching host toolchain to run each Native target.

Start PostgreSQL, Redis, and RabbitMQ:

```shell
docker compose up -d postgres redis rabbitmq
```

Configure and run the server:

```shell
export DATABASE_URL='jdbc:postgresql://localhost:5432/device_as_mcp'
export DATABASE_USER='device_as_mcp'
export DATABASE_PASSWORD='device_as_mcp'
export REDIS_URL='redis://localhost:6379'
export RABBITMQ_URL='amqp://device_as_mcp:device_as_mcp@localhost:5672'

./gradlew :server:run
```

Run the frontend development server:

```shell
./gradlew :web:wasmJsBrowserDevelopmentRun
```

Default development endpoints:

- Server: `http://localhost:8080`.
- Frontend: `http://localhost:8081`.
- PostgreSQL: `localhost:5432`.
- Redis: `localhost:6379`.
- RabbitMQ: `localhost:5672`.
- RabbitMQ management: `http://localhost:15672`.

The server falls back to process-local stores when the database, Redis, and
RabbitMQ URLs are omitted. This mode is only for single-process development.

## Server configuration

- `DEVICE_AS_MCP_ENVIRONMENT`: `development` or `production`; default
  `development`.
- `DEVICE_AS_MCP_HOST`: bind host; default `0.0.0.0`.
- `DEVICE_AS_MCP_PORT`: bind port; default `8080`.
- `DEVICE_AS_MCP_PUBLIC_URL`: externally visible server URL.
- `DEVICE_AS_MCP_FRONTEND_URL`: externally visible frontend URL.
- `DATABASE_URL`, `DATABASE_USER`, `DATABASE_PASSWORD`: PostgreSQL connection.
- `REDIS_URL`: Redis connection URL.
- `RABBITMQ_URL`: RabbitMQ AMQP URL.
- `GITHUB_CLIENT_ID`, `GITHUB_CLIENT_SECRET`: enable GitHub login.
- `OAUTH_PRE_REGISTERED_CLIENTS`: JSON array of OAuth client metadata.
- `DEVICE_AS_MCP_LOG_LEVEL`: server log level; default `INFO`.

Example pre-registered OAuth client:

```shell
export OAUTH_PRE_REGISTERED_CLIENTS='[
  {
    "client_id": "desktop-client",
    "client_name": "Desktop",
    "redirect_uris": ["http://127.0.0.1:9876/callback"]
  }
]'
```

Non-loopback public URLs must use HTTPS. Production mode additionally requires
PostgreSQL, Redis, RabbitMQ, and HTTPS for both public URLs. The GitHub client
ID and secret must be configured together.

Health endpoints:

- `/health/live`: process liveness.
- `/health/ready`: PostgreSQL, Redis, and RabbitMQ readiness.
- `/health`: readiness-compatible alias.

## Frontend deployment

Build the production assets:

```shell
./gradlew :web:wasmJsBrowserDistribution
```

Serve `web/build/dist/wasmJs/productionExecutable/` as static files.
`web/Dockerfile` builds the same assets and serves them with Caddy.

Configure a separate backend origin by setting the
`device-as-mcp-server` meta tag in `index.html`. When it is empty, the frontend
uses its own origin in production and `http://localhost:8080` from the common
local development ports.

The backend allows credentialed management requests only from
`DEVICE_AS_MCP_FRONTEND_URL`.

## Daemon

Build a release binary on a supported host:

```shell
./gradlew :daemon:linkReleaseExecutableLinuxX64
./gradlew :daemon:linkReleaseExecutableLinuxArm64
./gradlew :daemon:linkReleaseExecutableMacosArm64
./gradlew :daemon:linkReleaseExecutableMingwX64
```

Generated binaries are under `daemon/build/bin/<target>/releaseExecutable/`.
macOS cinterop must be built on macOS.

The management panel generates tokenized one-click commands for every supported
target. The installers download the latest GitHub Release binary, verify its
SHA-256 checksum, enroll the device, and configure user-login startup through
systemd, launchd, or the Windows Startup folder. The panel supplies its own
server URL automatically, and the target device uses its hostname as the initial
display name. Enrolled devices can be renamed from the panel.

Enroll with a one-time token generated in the management panel:

```shell
device-as-mcp enroll \
  --server https://mcp.example.com \
  --token TOKEN
```

Or omit `--token` to start browser-assisted enrollment:

```shell
device-as-mcp enroll --server https://mcp.example.com
```

Use `--name` only to override the device-derived default name.
Add `--no-run` to save the enrollment and exit without starting the persistent
connection. The platform installers use this before starting the login service.

Start an enrolled daemon:

```shell
device-as-mcp run
```

Credentials are stored in `~/.device-as-mcp/daemon.json`.

- POSIX directories use mode `0700`.
- POSIX credential files use mode `0600`.
- Windows credentials receive a current-user-only ACL.
- The daemon runs commands and file operations with its OS user's permissions.

## Remote MCP

- Endpoint: `POST /mcp`.
- Protocol revision: `2026-07-28`.
- Authentication: `Authorization: Bearer`.
- Accepted credentials: interactive OAuth access tokens and panel-issued auth keys.
- Resource metadata: `/.well-known/oauth-protected-resource/mcp`.
- Authorization-server metadata: `/.well-known/oauth-authorization-server`.
- Dynamic client registration: `POST /oauth/register`.
- Authorization flow: authorization code with PKCE `S256`.

`GET /mcp` and `DELETE /mcp` return `405 Method Not Allowed`. The endpoint is
stateless and does not issue `Mcp-Session-Id`.

Tools:

- `list_device`
- `launch_terminal_session`
- `terminal_session_input`
- `terminal_session_output`
- `launch_file_transfer`
- `file_transfer_status`
- `cancel_file_transfer`

Terminal behavior:

- `tty=false` uses separate process pipes.
- `tty=true` uses POSIX PTY or Windows ConPTY.
- Commands completing within two seconds return output and exit code directly.
- Longer commands return a session ID.
- Unread output is limited to 256 KiB per session.
- Ended sessions remain readable for 30 minutes.

File-transfer behavior:

- Paths are exact final source and destination paths.
- Files move device-to-device through the server relay, not through the MCP client.
- Symbolic links, junctions, unsupported names, and case collisions are skipped.
- Regular files stream serially with SHA-256 and byte-count verification.
- A failed file stream retries once from byte zero.
- Failed or cancelled transfers keep already written destination content.
- Successful transfer state is removed; an absent status is intentionally ambiguous.

## Gateway requirements

Production gateways must:

- Preserve long-lived `/daemon/connect` SSE responses.
- Disable proxy buffering for daemon SSE.
- Route every `/relay/**` request to the exact instance named by
  `X-Relay-Instance-Id`.
- Maintain an `instanceId` to backend mapping from service discovery and
  each backend's `/health` response.
- Stream relay request and response bodies without buffering.
- Use timeouts that permit long file streams.
- Forward `Authorization`, MCP metadata, device credential, and relay headers.
- Optionally apply user-based affinity to management and MCP requests.

Correctness does not depend on user affinity. It does depend on exact relay
routing for the lifetime of an active file transfer.

Daemon result POSTs may land on any server instance; the server routes them
through the current connection owner to the operation origin.

## Containers

Build and run the server profile with the development middleware:

```shell
docker compose --profile app up --build server
```

For the single-instance production topology, copy `.env.production.example` and
follow [`ops/production.md`](ops/production.md). It includes the Compose
deployment, HTTPS gateway, health checks, upgrades, backups, and restores.

## Verification

Run host-independent tests:

```shell
./gradlew :shared:jvmTest :server:test
```

Run middleware integration tests after starting Compose:

```shell
DEVICE_AS_MCP_RUN_INTEGRATION_TESTS=true \
  ./gradlew :server:test \
  --tests io.github.stream29.mcp.device.server.MiddlewareIntegrationTest
```

Run Linux Native shared tests and build the Wasm production bundle:

```shell
./gradlew :shared:linuxX64Test :web:wasmJsBrowserDistribution
```

Run Native binaries and tests on their matching operating systems before
publishing platform artifacts.

Pushing a `v*` tag runs `.github/workflows/release.yml`. The workflow validates
the project on Linux, builds all four native targets on matching GitHub-hosted
runners, and publishes the binaries, installers, and `SHA256SUMS`.
