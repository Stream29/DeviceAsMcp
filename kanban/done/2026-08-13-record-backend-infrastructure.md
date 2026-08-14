# Task Tree

- [done] Record the backend infrastructure
  - [done] Add the confirmed infrastructure to the project checklist
  - [done] Validate the record

# Details

- Record the decision without creating infrastructure configuration.
- The confirmed middleware set is PostgreSQL, Redis, and RabbitMQ.
- Redis handles device ownership, leases, fencing, and ephemeral transfer state.
- RabbitMQ handles cross-instance control-plane RPC.
- The development Compose topology includes PostgreSQL, Redis, and RabbitMQ.
- Keep middleware versions and detailed Compose configuration undecided.
- The infrastructure record passed content and whitespace checks.
