# Task Tree

- [done] Deploy the production service to Aliyun ECS
  - [done] Inspect host capacity and services
  - [done] Repair outbound DNS resolution
  - [done] Install Docker Engine and Compose
  - [done] Create the GitHub OAuth application
  - [done] Configure production secrets
  - [done] Deploy the production Compose stack
  - [done] Verify HTTPS, health, login, and installers

# Details

- Deploy the public repository from GitHub.
- Use the user-selected temporary `sslip.io` hostname.
- Store the GitHub OAuth secret only in the ECS production environment file.
- Keep PostgreSQL, Redis, RabbitMQ, and the application server private to the
  Compose network.
