# Material 3 Management UI

- Audited against the applicable Material Design 3 foundations and components.
- Controls that do not exist in this product are outside the audit scope.

## Foundations

- Color
  - Uses complete semantic light and dark color roles.
  - Pairs container and content roles with accessible contrast.
  - Follows the browser color-scheme preference.
  - Uses color with text or icons rather than as the only state indicator.
- Typography
  - Uses display, headline, title, body, and label roles consistently.
  - Limits reading width and preserves browser zoom behavior.
- Shape
  - Uses one five-step shape scale.
  - Reserves larger shapes for page-level and task-level containers.
- Elevation
  - Uses tonal surface containers as the primary hierarchy signal.
  - Keeps shadow elevation at one or two density-independent pixels.
- Iconography
  - Uses consistent 24-unit outlined symbols.
  - Gives standalone semantic images an accessible role and label.

## Layout and Navigation

- Uses a navigation bar below 600 dp and a navigation rail at 600 dp or wider.
- Uses a supporting-pane layout at 900 dp or wider.
- Stacks the same panes on smaller windows without removing functionality.
- Puts device installation before the empty inventory on compact windows.
- Constrains management content to 1280 dp.
- Keeps top-level routes semantic:
  - `/login`
  - `/devices`
  - `/connect`
- Redirects the legacy `/auth-keys` route to `/connect`.
- Keeps the app bar and primary navigation fixed while page content scrolls.
- Uses a document `main` landmark.

## Hierarchy and Components

- Gives each task one dominant action.
- Uses a single-choice segmented control to make OAuth and access keys explicit,
  mutually exclusive connection flows.
- Treats OAuth as the default and preferred MCP connection flow.
- Treats named, revocable access keys as a fallback and provides a primary
  action that copies the complete Codex configuration.
- Uses Material buttons, text fields, filter chips, cards, navigation, dialogs,
  progress indicators, and snackbars for their intended roles.
- Uses confirmation dialogs before destructive device and key revocation.
- Uses inline errors for failures and snackbars for transient success feedback.
- Uses purpose-built empty states instead of bare placeholder text.

## Interaction and Motion

- Relies on Material components for hover, focus, pressed, selected, and
  disabled state layers.
- Shows platform selection with both container change and a check symbol.
- Shows device connectivity with both a dot and an explicit label.
- Keeps controls disabled while their operation is in flight.
- Uses a short destination crossfade.
- Disables the crossfade when reduced motion is requested.
- Keeps generated commands and tokens selectable and directly copyable.

## Accessibility

- Uses built-in minimum interactive sizing for controls.
- Labels text inputs, standalone images, errors, and status.
- Marks page and section headings for semantic navigation.
- Adds required heading levels to the Compose web semantic overlay.
- Removes the invalid explicit role emitted on the Compose backing canvas.
- Supports keyboard entry, submission, navigation, and dialog dismissal through
  Material components.
- Lighthouse passed accessibility, best practices, SEO, and agentic browsing at
  100 after the web-semantics compatibility repair.

## Validation

- Inspected 390 dp compact, 768 dp medium, and 1440 dp expanded layouts.
- Inspected light and dark color schemes.
- Exercised registration, empty inventory, one real API-backed device, command
  generation, MCP endpoint display, key creation, and revocation confirmation.
- Existing Wasm browser tests and the optimized browser distribution pass.
- The rebuilt web image is live through the MacBook ngrok endpoint.
- Public liveness, readiness, SPA routes, and the rendered login scene pass.

## References

- [Material Design 3 in Compose](https://developer.android.com/develop/ui/compose/designsystems/material3)
- [Material 3 canonical layouts](https://m3.material.io/foundations/layout/canonical-examples/overview)
- [Material interaction states](https://m3.material.io/foundations/interaction/states/overview)
- [Compose accessibility defaults](https://developer.android.com/develop/ui/compose/accessibility/api-defaults)
- [Accessibility principles](https://developer.android.com/guide/topics/ui/accessibility/principles)
