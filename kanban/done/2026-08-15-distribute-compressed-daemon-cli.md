# Task Tree

- [done] Distribute compressed daemon CLI artifacts
  - [done] Inspect current release and installer flow
  - [done] Define per-platform archive contract
  - [done] Update release artifact packaging
    - [done] Package POSIX binaries as `tar.gz`
    - [done] Package the Windows binary as `zip`
    - [done] Publish checksums for compressed archives
  - [done] Update installer download and verification
    - [done] Verify archives before extraction
    - [done] Extract only the expected executable
    - [done] Preserve atomic executable replacement
  - [done] Update durable distribution documentation
  - [done] Validate archives and installers
    - [done] Validate workflow and script syntax
    - [done] Build representative native archives
    - [done] Verify archive contents and size reduction

# Details

- Replace direct distribution of raw native executables with compressed release archives.
- Use archive compression rather than executable packers such as UPX.
- Publish `tar.gz` archives for Linux and macOS and a `zip` archive for Windows.
- Put only `device-as-mcp` or `device-as-mcp.exe` at the archive root.
- Verify the downloaded archive against `SHA256SUMS` before extraction.
- Preserve checksum verification and one-click installation.
- Local release artifacts show approximately 62% to 66% size reduction.
- The release and installer changes are fully specified.
- Linux x64, Linux ARM64, and Windows x64 release binaries build successfully.
- Windows PowerShell parsing and native ZIP creation/extraction pass in the Windows 11 VM.
- Do not commit the changes.
