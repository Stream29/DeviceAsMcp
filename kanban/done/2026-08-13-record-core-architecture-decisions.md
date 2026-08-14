# Task Tree

- [done] Record the core architecture decisions
  - [done] Add the confirmed decisions to the project checklist
  - [done] Validate the record

# Details

- Record only the decisions confirmed by the user.
- Shared wire schemas use `kotlinx.serialization` and sealed interfaces and are finalized with their implementation slices.
- Login supports username/password and GitHub OAuth; ordinary login-session mechanics follow established framework patterns.
- The decisions passed content and whitespace checks.
