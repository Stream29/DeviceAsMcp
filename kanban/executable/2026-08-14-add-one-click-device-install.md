# Task Tree

- Add tokenized one-click device installation
  - [done] Add daemon enrollment-only execution
  - [done] Add POSIX and Windows installers
    - [done] Detect the supported target platform
    - [done] Verify downloaded release checksums
    - [done] Enroll and start at user login
  - [done] Add GitHub native release automation
    - [done] Build all four native targets
    - [done] Publish binaries, installers, and checksums
  - [done] Add platform commands to the panel
    - [done] Generate one single-use enrollment token
    - [done] Render and copy each platform command
  - [done] Record the durable installation design
  - Validate builds, scripts, and commands
  - Publish and verify the first release

# Details

- The management panel must show platform-specific one-click install commands.
- Each command must include the generated enrollment token.
- Installers must download the required files from GitHub.
- Supported targets follow the existing daemon targets: Linux x64, Linux ARM64,
  macOS ARM64, and Windows x64.
- Enrollment tokens are already single-use and expire after ten minutes.
- GitHub Releases will carry version-consistent native binaries, installer
  scripts, and SHA-256 checksums.
- POSIX installers will use a systemd user service on Linux and a LaunchAgent
  on macOS. The Windows installer will use the current user's Startup folder.
