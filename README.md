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

## Main env

- `DB_URL`, `DB_USER`, `DB_PASSWORD`
- `JWT_SECRET`, `HASH_PEPPER`
- `XRAY_MODE=stub|grpc`
- `app.quota.freeGbPerMonth` in `application.yaml` (default `10`)

## Main endpoints

- `POST /auth/guest`
- `POST /auth/upgrade`
- `POST /auth/register`
- `POST /auth/login`
- `POST /auth/refresh`
- `POST /devices/bind`
- `GET /nodes`
- `POST /vpn/issue`
- `POST /vpn/revoke`
- `GET /policy/current`
- `PUT /policy/current`
- `GET /quota/current`
- `POST /admin/nodes`
- `PATCH /admin/nodes/{id}`
- `POST /admin/quota/grant`
- `GET /health`
- `GET /openapi.json`
- `GET /swagger`

## Notes

- Guest user can consume free monthly quota without registration.
- Additional purchased quota can be granted only to `REGISTERED` users.
- Current Xray integration mode is `stub`; `grpc` mode is scaffolded.
