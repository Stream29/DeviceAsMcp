# Task Tree

- [done] Migrate production from Aliyun ECS to the MacBook
  - [done] Inspect both deployment environments
  - [done] Preserve production data and secrets
  - [done] Prepare the MacBook deployment
  - [done] Restore and verify the application
  - [done] Configure persistent ngrok ingress
  - [done] Update public OAuth configuration
  - [done] Remove the Aliyun deployment
  - [done] Record the migrated production state

# Details

- Keep PostgreSQL data while moving the deployment.
- Treat Redis and RabbitMQ state as replaceable.
- Verify the ngrok endpoint before removing the Aliyun stack.
- Run the existing production Compose stack on the MacBook.
- Terminate public TLS at ngrok and forward to a loopback-only Caddy HTTP port.
- Reuse the existing production credentials without exposing them locally.
- Use a PostgreSQL custom-format dump for the durable-data migration.
- Start ngrok and the Compose stack automatically after user login.
- The MacBook has Docker Desktop 29.2.0, Compose 5.0.2, ngrok 3.22.1,
  24 GiB of memory, and sufficient free disk space.
- IntelliJ IDEA is running on another repository, not DeviceAsMcp.
- The assigned ngrok dev domain is
  `weedy-priestliest-edwina.ngrok-free.dev`.
- The final PostgreSQL dump was transferred with matching SHA-256 hashes and
  restored with the expected row counts.
- Public liveness, readiness, frontend routing, OAuth metadata, and GitHub
  login pass through the ngrok endpoint.
- The ngrok LaunchAgent recovered from a forced process termination.
- The Compose stack and public endpoint recovered after Docker Desktop was
  fully stopped and started by its LaunchAgent.
- The Aliyun Compose project, containers, volumes, application images,
  deployment directory, secrets, and backups were removed.
- Reusable operational details are recorded in
  `shared-context/findings/macbook-ngrok-production.md`.
