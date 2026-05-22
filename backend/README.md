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

- `MOSCOW_DEPLOY_PATH` (defaults to `/opt/vpn-control-plane`; a legacy trailing `/backend` is normalized away)

The server must already have Docker Compose and a production `.env` file in the deploy root. Deployment uploads the `backend/` sources over SSH and runs with a fixed Compose project name so the PostgreSQL volume stays `vpn-control-plane_pg_data`:

```bash
docker compose -p vpn-control-plane -f docker-compose.prod.yml up -d --build --remove-orphans
```

## Main env

- `DB_URL`, `DB_USER`, `DB_PASSWORD`
- `JWT_SECRET`, `HASH_PEPPER`
- `XRAY_MODE=stub|grpc`
- `VPN_DEFAULT_ROUTE_MODE=CASCADE|SINGLE` (default `CASCADE`)
- `VPN_CASCADE_FALLBACK_TO_SINGLE=true|false` (default `true`)
- `app.quota.freeGbPerMonth` in `application.yaml` (default `10`)

## Main endpoints

- `POST /auth/guest`
- `GET /auth/profile`
- `POST /auth/upgrade`
- `POST /auth/register`
- `POST /auth/login`
- `POST /auth/refresh`
- `POST /devices/bind`
- `GET /client/settings`
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
- Android clients read `/client/settings` before issuing configs. With `VPN_DEFAULT_ROUTE_MODE=CASCADE`, `/vpn/issue` provisions ENTRY and EXIT hops when a healthy entry node exists.
- If no entry node is available and `VPN_CASCADE_FALLBACK_TO_SINGLE=true`, `/vpn/issue` returns `requestedRouteMode=CASCADE`, `routeMode=SINGLE`, and `routeFallbackReason=NO_ENTRY_NODES`.
- On startup the backend creates an admin user. Use `ADMIN_EMAIL` and `ADMIN_PASSWORD` to override defaults.
- Synthetic seed data is disabled by default. Set `SEED_SYNTHETIC=true` only for local/demo data.

## Cascade smoke checks

After deployment, verify the control-plane contract with a bound device token:

```bash
curl -H "Authorization: Bearer $TOKEN" "$API_BASE/client/settings"
curl -X POST "$API_BASE/vpn/issue" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: $(uuidgen)" \
  -d '{"deviceId":"'"$DEVICE_ID"'","routeMode":"CASCADE","forceRotate":true}'
```

Expected CASCADE response: `routeMode=CASCADE`, two `hops`, and `clientConfig.route.final` set to the EXIT tag. Expected fallback response, when fallback is enabled and no entry node is healthy: `requestedRouteMode=CASCADE`, `routeMode=SINGLE`, `routeFallbackReason=NO_ENTRY_NODES`.
