# Task Tree

- [done] Connect Kodex and refine GitHub login
  - [done] Connect Kodex first
    - [done] Create a scoped access key for the existing user
    - [done] Add a Streamable HTTP server to Codex settings
    - [done] Verify the key through a modern `tools/list`
    - [done] Add Codex protocol compatibility
      - [done] Record the dual-protocol decision
      - [done] Accept stateless 2025-06-18 requests
      - [done] Preserve strict 2026-07-28 validation
      - [done] Deploy the compatible server
    - [done] Verify all remote MCP tools in Kodex
  - [done] Refine the GitHub login action
    - [done] Remove the action from the login card
    - [done] Add an equal-width action below the card
    - [done] Label the action `Login by GitHub`
  - [done] Validate and deploy
    - [done] Run focused web checks
    - [done] Rebuild the MacBook web service
    - [done] Verify the public login page

# Details

- Complete and verify the Kodex connection before changing the login UI.
- Use `https://weedy-priestliest-edwina.ngrok-free.dev/mcp`.
- Kodex inherits MCP servers from `~/.codex/config.toml`.
- Store the bearer key only in private Codex settings and the server-side hash.
- Codex recognizes the server and an authenticated `tools/list` returns all seven tools.
- Codex 0.147.0 sends a 2025-06-18 `initialize` request.
- The user chose a server-side compatibility path while retaining 2026-07-28.
- The implementation and validation route is fully determined.
- Do not commit the changes.
