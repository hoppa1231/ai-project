# VPN Control Plane (MVP)

Kotlin + Ktor backend for VPN control-plane with PostgreSQL, Flyway, JWT auth, device binding, guest-first onboarding and monthly quota management.

## Quick start (dev)

```bash
docker compose up -d postgres
```

Then run backend locally:

```bash
./gradlew run
```

## CI/CD Moscow

GitHub Actions workflow: `.github/workflows/backend-moscow.yml`.

It runs `./gradlew --no-daemon clean build` for backend changes, then deploys to the `moscow` environment on pushes to `product` or manual `workflow_dispatch`.

Required GitHub secrets:

- `MOSCOW_SSH_HOST`
- `MOSCOW_SSH_USER`
- `MOSCOW_SSH_PRIVATE_KEY`
- `MOSCOW_SSH_PORT` (optional, defaults to `22`)

Optional GitHub environment/repository variable:

- `MOSCOW_DEPLOY_PATH` (defaults to `/opt/vpn-control-plane/backend`)

The server must already have Docker Compose and a production `.env` file in `MOSCOW_DEPLOY_PATH`. Deployment uploads the `backend/` sources over SSH and runs:

```bash
docker compose -f docker-compose.prod.yml up -d --build --remove-orphans
```

## Main env

- `DB_URL`, `DB_USER`, `DB_PASSWORD`
- `JWT_SECRET`, `HASH_PEPPER`
- `XRAY_MODE=stub|grpc`
- `app.quota.freeGbPerMonth` in `application.yaml` (default `10`)

## Main endpoints

- `POST /auth/guest`
- `GET /auth/profile`
- `POST /auth/upgrade`
- `POST /auth/register`
- `POST /auth/login`
- `POST /auth/refresh`
- `POST /devices/bind`
- `GET /nodes`
- `POST /vpn/issue`
- `GET /vpn/configs`
- `POST /vpn/revoke`
- `GET /policy/current`
- `PUT /policy/current`
- `GET /quota/current`
- `POST /admin/nodes`
- `GET /admin/nodes`
- `PATCH /admin/nodes/{id}`
- `POST /admin/quota/grant`
- `GET /admin` minimal browser admin panel
- `GET /health`
- `GET /openapi.json`
- `GET /swagger`

## Notes

- Guest user can consume free monthly quota without registration.
- Additional purchased quota can be granted only to `REGISTERED` users.
- Current Xray integration mode is `stub`; `grpc` mode is scaffolded.
- On startup the backend creates an admin user. Use `ADMIN_EMAIL` and `ADMIN_PASSWORD` to override defaults.
- Synthetic seed data is disabled by default. Set `SEED_SYNTHETIC=true` only for local/demo data.
