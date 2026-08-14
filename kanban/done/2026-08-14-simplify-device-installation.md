# Task Tree

- [done] Simplify device installation and naming
  - [done] Derive the server URL automatically
    - [done] Remove the panel server URL field
    - [done] Keep the development origin override
  - [done] Generate initial names on the device
    - [done] Omit names from generated commands
    - [done] Retain optional installer name overrides
  - [done] Add authenticated device renaming
    - [done] Add the shared rename request
    - [done] Update in-memory and PostgreSQL stores
    - [done] Add the management API and UI
  - [done] Validate and deliver
    - [done] Run focused and full checks
    - [done] Publish the corrected release
    - [done] Redeploy and verify production

# Details

- The management panel derives the server URL from its own deployment origin.
- One-click commands contain no device name.
- Installers derive the initial name from the target device.
- Users can rename owned devices after enrollment.
