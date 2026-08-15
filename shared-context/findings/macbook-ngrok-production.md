# MacBook ngrok Production

- The production service runs on the remote Apple Silicon MacBook.
- Public endpoint: `https://weedy-priestliest-edwina.ngrok-free.dev`.
- The repository is at `~/ACodeSpace/push/DeviceAsMcp`.
- Private deployment configuration and the cutover backup are under
  `~/Library/Application Support/DeviceAsMcp`.
- HTTPS terminates at ngrok.
- Caddy listens only through `127.0.0.1:8080` on the MacBook.
- The Compose services use `restart: unless-stopped`.
- `io.github.stream29.deviceasmcp.docker-start` starts Docker Desktop after
  user login.
- `io.github.stream29.deviceasmcp.ngrok` keeps the ngrok endpoint running.
- Docker Desktop and the complete Compose stack recovered after a hard reboot.
- The ngrok agent recovered in forced-termination tests, but one hard reboot
  started it before DNS was usable and required a `launchctl kickstart` after
  networking recovered.
- The GitHub OAuth homepage and callback use the ngrok endpoint.
- Browser users see ngrok's free-tier warning once and must select
  `Visit Site`.
- The MacBook must be awake with the user logged in for the service to remain
  available.
- The previous Aliyun Compose project, volumes, images, secrets, backups, and
  deployment directory were removed after cutover validation.
- Caddy runtime logs remove authorization headers, cookies, device secrets, and
  OAuth query credentials.
- The production server and Material 3 management UI were rebuilt from the
  `v0.1.5` snapshot on 2026-08-15.
- Production verification covered editable device descriptions through the
  management API, modern MCP, and Codex-compatible MCP.
- Production verification also covered named access keys, device revocation
  and immediate credential invalidation, empty device-list invalidation events,
  and proxy-log redaction.
- The `v0.1.5` daemon release uses Kotlin/Native CMS on every target.
