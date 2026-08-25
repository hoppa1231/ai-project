# Roadmap

Roadmap items are ordered by risk reduction and portfolio value, not feature count.

## P0 — Release safety

- Remove debug signing from Android release builds and use CI-provided signing material.
- Reject placeholder JWT, pepper, database, and agent secrets in production mode.
- Add PostgreSQL integration tests for auth, quota, device binding, and migrations.
- Add a fake node-agent contract suite covering timeouts, retries, and partial provisioning.
- Make issue/revoke operations idempotent and persist provisioning state transitions.

## P1 — Operability and scale

- Export request latency, error rate, healthy-node count, provisioning failures, and worker staleness.
- Add OpenTelemetry traces across API-to-agent calls and define SLOs/runbooks.
- Introduce per-node credentials or mTLS with rotation.
- Coordinate background workers for multiple backend replicas.
- Add database backup/restore drills and migration rollback procedures.

## P2 — Product maturity

- Add reproducible Android emulator screenshots and an end-to-end demo recording.
- Expand Compose UI tests for adaptive layouts and VPN state transitions.
- Add operator-facing policy and node management UI.
- Publish versioned API compatibility and mobile release policy.

## P3 — Bounded AI assistance

- Prototype read-only incident summaries using synthetic, redacted telemetry.
- Version prompts and build an evaluation dataset before connecting live signals.
- Require schema validation and approval-gated typed actions.
- Measure investigation-time improvement against deterministic dashboards and runbooks.

## Explicit non-goals

- AI-driven authorization or autonomous credential provisioning.
- Microservice extraction without measured scaling or ownership pressure.
- Hiding known production gaps behind demo-only mocks.
