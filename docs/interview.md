# Interview Guide

## Five-minute project explanation

### 1. Problem

A real VPN product needs more than a tunnel protocol. It must manage identity, devices, node health, quotas, configuration rotation, traffic accounting, and revocation without exposing infrastructure panels to mobile clients.

### 2. Solution

SecureVPN is a distributed access platform. A Compose Android app talks to a Kotlin/Ktor control plane. The backend stores durable state in PostgreSQL, selects healthy routes under policy and quota constraints, and delegates vendor-specific provisioning to an authenticated agent on each Xray/3x-ui node. The app starts sing-box using the server-rendered config and only reports success after the runtime confirms the tunnel is on.

### 3. Architecture

The control plane is a modular monolith with route, service/adapter, and repository boundaries. This keeps security-sensitive state changes transactional. The node agent is an anti-corruption layer around 3x-ui. Flyway owns schema evolution, OpenAPI owns the HTTP contract, and CI validates the three deployable components.

### 4. Technical challenges

- Accounting must survive config rotation, so quota is tied to durable identity/device records and reconciled from node counters.
- Node health is dynamic, so issuing a config combines stored policy with live eligibility and fails closed when no safe route exists.
- A foreground-service start call does not prove a VPN works; the app waits for sing-box `STATE_ON`, handles explicit failure, and uses a bounded startup timeout.
- Vendor panels may use custom base paths, so the node adapter contains fallback behavior rather than leaking it into business logic.

### 5. Engineering decisions

I chose a modular monolith for transaction consistency and delivery speed, PostgreSQL for relational/audit state, a per-node agent to isolate credentials and vendor APIs, and deterministic code—not an LLM—for authorization, quota, and routing decisions.

### 6. Future improvements

The highest-value work is production secret validation, real adapter integration tests, idempotent provisioning, observability/SLOs, mTLS, Android release signing, and coordinated workers. AI could later summarize redacted incidents, but only as an advisory component with evaluations and approval gates.

## Likely questions and strong answers

### Why a modular monolith?

The main operations cross identity, quota, config, and audit state. A single process and database make those invariants transactional. Package boundaries preserve extraction options. I would split services only when scaling characteristics or team ownership justify distributed consistency costs.

### Why Kotlin and Ktor?

Kotlin gives strong null safety, concise immutable models, coroutines, and language consistency with Android. Ktor has a small explicit plugin model and good test-host support. The trade-off is a smaller ecosystem than Spring, which is acceptable for this focused control plane.

### Why PostgreSQL?

The domain is relational and consistency-sensitive: users own devices and configs; grants and usage form an auditable ledger; nodes and policies are versioned. PostgreSQL provides transactions, constraints, indexing, and mature operations.

### Why is the node agent necessary?

It keeps panel credentials on the node, narrows the remote API, and isolates 3x-ui quirks. The control plane depends on a provisioning capability rather than a vendor API. This improves security and replaceability.

### How do you prevent quota reset on config rotation?

I model accounting against durable user/device ownership and store inventory mappings for transient node client IDs. Reconciliation applies traffic deltas to the monthly aggregate, so replacing a credential does not create a new allowance.

### How would you scale it?

First make provisioning idempotent and move background work to coordinated jobs. Then run stateless API replicas behind a load balancer, keep PostgreSQL authoritative, add measured read caching, and partition workers by node. I would not split the database first.

### What happens when a node fails during issue?

Eligibility excludes unhealthy nodes before planning. A provisioning failure must not become an active config. The next step is a persisted state machine with idempotency keys so retries and compensation are safe, especially for cascade routes.

### Why not use AI for route selection?

Access and routing policy need explainable, reproducible outcomes and hard constraints. A model can summarize anomalous telemetry for operators, but deterministic validation must remain authoritative. Probabilistic output cannot grant access or create credentials.

### What are the current production gaps?

The main ones are debug Android release signing, placeholder-secret enforcement, insufficient database/adapter integration tests, shared-token agent authentication, limited metrics/tracing, and in-process worker coordination. They are explicitly prioritized in the roadmap.

### How would you test the system end to end?

Start PostgreSQL from Testcontainers, migrate from an empty schema, run the Ktor test application, and connect it to a stateful fake node agent. Cover guest onboarding through issue, counter reconciliation, quota denial, revoke, retries, and node failure. Android tests should separately verify API contract parsing and runtime state transitions.
