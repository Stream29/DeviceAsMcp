# Task Tree

- Fix management-panel layout and routing
  - [done] Identify the white-edge cause
  - [done] Identify the missing navigation model
  - [done] Define semantic management routes
  - [done] Remove viewport edge artifacts
  - [done] Add browser-history routing
    - [done] Canonicalize the root route
    - [done] Handle direct loads and popstate
    - [done] Redirect on authentication changes
  - [done] Split devices and auth-key scenes
  - [done] Preserve authentication redirects
    - [done] Target OAuth login redirects at `/login`
    - [done] Retain authorization return state
  - Validate navigation and production rendering
    - [done] Add route and redirect tests
    - [done] Build the Wasm distribution
    - [done] Verify rendered viewport edges
    - Redeploy and verify direct routes

# Details

- `ComposeViewport(document.body)` removes the style element currently placed
  inside the body, restoring the browser's default white body and margin.
- The frontend currently switches login and dashboard content from local state
  without representing those scenes in browser history.
- Use `/login`, `/devices`, and `/auth-keys` as the management routes.
- Support direct loading, refresh, and browser back/forward navigation.
