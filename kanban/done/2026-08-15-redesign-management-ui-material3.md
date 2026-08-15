# Task Tree

- [done] Redesign the management UI with Material Design 3
  - [done] Audit applicable Material 3 guidance
  - [done] Define the adaptive information architecture
  - [done] Rename the connection destination
    - [done] Add the canonical `/connect` route
    - [done] Redirect the legacy `/auth-keys` route
    - [done] Replace user-facing auth-key terminology
  - [done] Establish the design system
    - [done] Add accessible light and dark color roles
    - [done] Define type and shape scales
    - [done] Use tonal surfaces and restrained elevation
  - [done] Redesign the authentication scene
    - [done] Establish a single primary authentication action
    - [done] Surface validation, progress, and errors
  - [done] Redesign the device scene
    - [done] Separate device inventory and installation
    - [done] Clarify empty, online, and editing states
    - [done] Present secure copyable install commands
  - [done] Redesign the MCP connection scene
    - [done] Lead with the canonical MCP endpoint
    - [done] Explain OAuth and access-key alternatives
    - [done] Protect key creation and revocation flows
  - [done] Add adaptive application navigation
    - [done] Use a navigation bar on compact windows
    - [done] Use a navigation rail on wider windows
    - [done] Constrain readable content widths
  - [done] Improve interaction and accessibility
    - [done] Preserve keyboard and pointer state feedback
    - [done] Label icons, headings, status, and errors
    - [done] Honor color-scheme and reduced-motion preferences
  - [done] Validate the implementation
    - [done] Build the Wasm distribution
    - [done] Run existing checks without changing tests
    - [done] Inspect compact and expanded rendering
  - [done] Deploy and verify the production UI
  - [done] Record the durable design decisions

# Details

- Rename the user-facing `MCP auth keys` destination to `Connect to MCP`.
- Preserve authentication, device enrollment, device renaming, and MCP
  credential behavior.
- Apply Material Design 3 systematically rather than only restyling individual
  controls.
- Use compact and expanded layouts appropriate for the browser viewport.
- Do not add or modify tests without explicit user authorization.
- Use a supporting-pane layout for device inventory and installation on wide
  screens, then stack the same content on compact screens.
- Use navigation bar and navigation rail components for the two top-level
  destinations.
- Keep `/auth-keys` as a compatibility alias and canonicalize it to `/connect`.
- Prefer OAuth-capable MCP clients; present revocable access keys as a fallback.
- Use generated Material color-role pairs, semantic typography roles, a
  consistent shape scale, tonal elevation, explicit status labels, minimum
  interactive component sizing, and reduced-motion awareness.
- The web image was rebuilt and deployed to the MacBook ngrok production
  endpoint without changing the server or persistent middleware.
- Public liveness, readiness, `/login`, `/devices`, `/connect`, and the legacy
  `/auth-keys` SPA route passed after deployment.
- The detailed audit and validation record is in
  `../../shared-context/findings/material3-management-ui.md`.
