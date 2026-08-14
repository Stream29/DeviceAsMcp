# Task Tree

- [done] Run production end-to-end tests on real devices
  - [done] Select three platform devices
  - [done] Prepare an isolated production test identity
    - [done] Back up the production database
    - [done] Register a uniquely named test user
    - [done] Issue enrollment tokens and an MCP key
  - [done] Repair native HTTPS connectivity
    - [done] Reproduce the released CIO TLS failure
    - [done] Select native engines without extra runtime setup
    - [done] Repair stale daemon-connection cleanup
    - [done] Build and test every native target
    - [done] Publish corrected release artifacts
  - [done] Connect Linux x64, macOS ARM64, and Windows x64
    - [done] Identify Aliyun ICP interception
    - [done] Route affected test devices through the tailnet
    - [done] Use published checksummed release binaries
    - [done] Keep daemon state in isolated test directories
    - [done] Confirm all three devices remain online together
  - [done] Verify device discovery and terminal operations
    - [done] List all three devices through MCP
    - [done] Run a short non-TTY command on each OS
    - [done] Exercise long-running input and output
    - [done] Exercise PTY or ConPTY execution
  - [done] Verify cross-device file transfer
    - [done] Transfer a file across operating systems
    - [done] Transfer a directory across operating systems
    - [done] Compare resulting content and hashes
  - [done] Remove test services, files, and production data
    - [done] Stop every test daemon
    - [done] Remove device-local test artifacts
    - [done] Delete only the prefixed production identity
    - [done] Restore the Windows VM power state
  - [done] Record reproducible results and defects

# Details

- Tested the local Linux x64 workstation, remote Apple Silicon MacBook, and
  local Windows 11 x64 VM with release `v0.1.2`.
- Verified every downloaded binary against the published `SHA256SUMS`.
- Kept all three devices online together and listed them through MCP.
- Passed short commands on every OS, long-running input/output, Linux PTY, and
  Windows ConPTY.
- Passed a Linux-to-macOS file transfer and a macOS-to-Windows directory
  transfer with matching SHA-256 hashes.
- Fixed the native CIO TLS failure and stale SSE connection cleanup found
  during testing.
- Recorded reusable results in
  `shared-context/findings/production-real-device-e2e.md`.
- Removed temporary routes, daemons, device files, Redis keys, and the isolated
  production identity.
- Restored the Windows VM to `shut off`; production services remained healthy.
- Retained the pre-test production database backup on the ECS with mode `600`.
