# Production Real-Device E2E

- Tested release `v0.1.2` from commit `76bccbc` on:
  - Linux x64 workstation.
  - Apple Silicon macOS MacBook.
  - Windows 11 x64 VM.
- Verified all published binaries against the release `SHA256SUMS`.
- Verified three simultaneous daemon connections through production.
- Verified MCP discovery, short commands on every OS, long-running terminal
  input/output, Linux PTY, and Windows ConPTY.
- Verified a Linux-to-macOS file transfer and a macOS-to-Windows directory
  transfer, including content hashes and an empty directory.

## Defects Found

- Release `v0.1.1` used Ktor CIO for Kotlin/Native HTTPS and failed before an
  HTTP response because native CIO did not support the required TLS session.
- Native clients now use Curl on Linux, Darwin on macOS, and WinHttp on
  Windows.
- A failed SSE keepalive previously left a stale in-memory daemon connection.
- The server now completes the outer SSE lifecycle when renewal or keepalive
  fails, then releases both the local connection and Redis owner lease.

## Deployment Limitation

- Direct traffic from the macOS and Windows test networks to the current
  `sslip.io` hostname is intercepted by Alibaba Cloud's ICP enforcement before
  reaching Caddy.
- The same public route was intermittently unusable from the Linux test host.
- Temporary Tailnet routes were sufficient for functional verification and
  were removed afterward.
- General public use requires an ICP-compliant domain and deployment path, or
  a non-mainland public ingress.
