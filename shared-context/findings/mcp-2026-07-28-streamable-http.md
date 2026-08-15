# MCP 2026-07-28 Streamable HTTP Findings

- Reference: [Streamable HTTP](https://modelcontextprotocol.io/specification/2026-07-28/basic/transports/streamable-http).
- The MCP endpoint accepts POST only.
- Each JSON-RPC message uses its own HTTP POST.
- A request receives one JSON response or one request-scoped SSE response.
- The revision removes protocol-level sessions and the standalone GET stream.
- `Mcp-Session-Id` and `Last-Event-ID` belong to earlier revisions and are ignored.
- Request-scoped SSE streams are not resumable.
- Closing a request-scoped SSE response cancels that request.
- Every request carries protocol metadata in its body and mirrored required HTTP headers.
- `MCP-Protocol-Version`, `Mcp-Method`, and applicable `Mcp-Name` values must match the request body.
- The server validates `Origin` when present.
- Request-scoped SSE responses should disable reverse-proxy buffering.
- Reference: [Authorization](https://modelcontextprotocol.io/specification/2026-07-28/basic/authorization).
- Authorization for HTTP transports is an OAuth 2.1 resource-server contract, not only an HTTP header convention.
- The MCP server publishes OAuth 2.0 Protected Resource Metadata that identifies at least one authorization server.
- A `401 Unauthorized` challenge uses the Bearer scheme and identifies the protected-resource metadata URL.
- The authorization server exposes standard OAuth or OpenID Connect discovery metadata.
- MCP clients obtain a client ID through Client ID Metadata Documents, pre-registration, or Dynamic Client Registration.
- Authorization-code clients use PKCE, with `S256` when technically capable.
- Authorization and token requests include a `resource` parameter identifying the canonical MCP server URI.
- OAuth access tokens use `Authorization: Bearer` on every request and never use URI query parameters.
- The MCP server validates that each access token was issued for its resource audience.
- Missing, invalid, or expired access tokens receive HTTP `401`; insufficient scopes receive HTTP `403`.
- A remote MCP token is not passed through to upstream services.
- The core Authorization specification does not define a proprietary auth key format.

## Project Application

- The project exposes one canonical `/mcp` resource URI for all users.
- The gateway validates the access token and may use the derived user identity for best-effort affinity.
- User affinity is an optimization only; Redis records the actual device-connection owner and cross-instance operations use RabbitMQ RPC fallback.
- The authorization server supports Client ID Metadata Documents, pre-registration, and Dynamic Client Registration.
- A management-panel auth key is a revocable, long-lived opaque access token issued for the remote MCP resource audience.
- Codex 0.147.0 initializes Streamable HTTP with protocol revision `2025-06-18`.
- The server accepts that revision as a stateless compatibility path only when the `2026-07-28` request metadata is absent.
- The compatibility path supports `initialize`, `notifications/initialized`, `tools/list`, and `tools/call` without issuing a session ID or exposing a GET stream.
- Codex and Kodex read this server from the private `~/.codex/config.toml` MCP configuration.
