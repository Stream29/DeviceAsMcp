# Task Tree

- Run production end-to-end tests on real devices
  - [done] Select three platform devices
  - [done] Prepare an isolated production test identity
    - [done] Back up the production database
    - [done] Register a uniquely named test user
    - [done] Issue enrollment tokens and an MCP key
  - Repair native HTTPS connectivity
    - [done] Reproduce the released CIO TLS failure
    - [done] Select native engines without extra runtime setup
    - [done] Repair stale daemon-connection cleanup
    - [done] Build and test every native target
    - Publish corrected release artifacts
  - Connect Linux x64, macOS ARM64, and Windows x64
    - [done] Identify Aliyun ICP interception
    - Route affected test devices through the tailnet
    - Use published checksummed release binaries
    - Keep daemon state in isolated test directories
    - Confirm all three devices remain online together
  - Verify device discovery and terminal operations
    - List all three devices through MCP
    - Run a short non-TTY command on each OS
    - Exercise long-running input and output
    - Exercise PTY or ConPTY execution
  - Verify cross-device file transfer
    - Transfer a file across operating systems
    - Transfer a directory across operating systems
    - Compare resulting content and hashes
  - Remove test services, files, and production data
    - Stop every test daemon
    - Remove device-local test artifacts
    - Delete only the prefixed production identity
    - Restore the Windows VM power state
  - Record reproducible results and defects

# Details

- Use the local Linux workstation, the remote Apple Silicon MacBook, and the
  local Windows 11 VM.
- Start the Windows VM temporarily and restore its original powered-off state.
- Exercise the deployed production server and published native artifacts.
- Use uniquely prefixed test data and remove it after verification.
- Do not leave daemon startup entries or test files on the devices.
- Direct access from the macOS and Windows test networks reaches Aliyun's ICP
  interception instead of Caddy; use temporary tailnet routing only for this
  test and remove it afterward.
