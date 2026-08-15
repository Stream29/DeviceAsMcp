# Production Operations

The provided production topology runs one server instance behind Caddy. Do not
scale `server` with this Compose file. Multi-instance deployment requires a
gateway that routes every relay request to its exact `X-Relay-Instance-Id`.

## Prerequisites

- Point the deployment domain's DNS records at the host.
- Allow inbound TCP ports 80 and 443 and UDP port 443.
- Install Docker Engine with Compose v2.
- Keep PostgreSQL backups outside the Docker host.

## Configure

```shell
cp .env.production.example .env.production
chmod 600 .env.production
```

- Replace every placeholder in `.env.production`.
- Use a bare domain without a scheme.
- Generate URL-safe passwords, for example with `openssl rand -hex 32`.
- Set both GitHub values or leave both empty.
- Keep `OAUTH_PRE_REGISTERED_CLIENTS` on one line when configured.

Validate the resolved Compose model:

```shell
docker compose \
  --env-file .env.production \
  -f compose.production.yaml \
  config --quiet
```

## Start

```shell
docker compose \
  --env-file .env.production \
  -f compose.production.yaml \
  up -d --build
```

Caddy obtains and renews the public TLS certificate. PostgreSQL, Redis,
RabbitMQ, and the JVM server are not published on host ports.

Check the deployment:

```shell
curl --fail --silent --show-error \
  "https://$(sed -n 's/^DEVICE_AS_MCP_DOMAIN=//p' .env.production)/health/live"
curl --fail --silent --show-error \
  "https://$(sed -n 's/^DEVICE_AS_MCP_DOMAIN=//p' .env.production)/health/ready"
docker compose \
  --env-file .env.production \
  -f compose.production.yaml \
  ps
```

- `/health/live` only verifies the JVM process.
- `/health/ready` and `/health` verify PostgreSQL, Redis, and RabbitMQ.

Inspect logs:

```shell
docker compose \
  --env-file .env.production \
  -f compose.production.yaml \
  logs --tail=200 server web
```

The provided Caddy configuration removes authorization headers, cookies,
device secrets, and OAuth query credentials from runtime logs.

## Upgrade

Create a PostgreSQL backup before upgrading. Then rebuild and recreate changed
services:

```shell
docker compose \
  --env-file .env.production \
  -f compose.production.yaml \
  build --pull server web
docker compose \
  --env-file .env.production \
  -f compose.production.yaml \
  up -d --remove-orphans
```

The single-server topology can have a short interruption while `server` is
recreated. Daemons reconnect and callers can retry interrupted operations.

## Backup

PostgreSQL is the durable application data store. Redis contains leases,
routing, and transfer state; RabbitMQ carries in-flight control messages.
Back up PostgreSQL:

```shell
mkdir -p backups
docker compose \
  --env-file .env.production \
  -f compose.production.yaml \
  exec -T postgres \
  pg_dump -U device_as_mcp -d device_as_mcp -Fc \
  > "backups/device-as-mcp-$(date -u +%Y%m%dT%H%M%SZ).dump"
```

Copy the resulting dump to storage outside the Docker host.

## Restore

Stop request handling, restore a selected dump, and restart:

```shell
docker compose \
  --env-file .env.production \
  -f compose.production.yaml \
  stop web server
docker compose \
  --env-file .env.production \
  -f compose.production.yaml \
  exec -T postgres \
  pg_restore --clean --if-exists --no-owner \
  -U device_as_mcp -d device_as_mcp \
  < backups/SELECTED.dump
docker compose \
  --env-file .env.production \
  -f compose.production.yaml \
  up -d server web
```

- Restore only into a compatible application version.
- Expect active terminal calls and file transfers to be lost.
- Daemons reconnect automatically after the services recover.
