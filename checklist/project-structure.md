# Project Structure

- Use `io.github.stream29.mcp.device` as the base namespace.
- Keep the root project free of source sets and platform targets.
- Add concrete modules only for explicitly requested capabilities.
- Declare shared plugin versions in `gradle/libs.versions.toml`.
