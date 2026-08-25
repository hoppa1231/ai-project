# Design Decisions

## Canonical backend

`backend/` is the canonical deployable control plane. The root `src/` tree is an older compatibility copy and should receive no new features. Removing it is intentionally deferred to a dedicated migration commit so deployment scripts and consumers can be checked first.

## Modular monolith before microservices

The platform has several domains, but its current scale does not justify independent deployment and distributed transactions. Package boundaries provide separation while PostgreSQL transactions keep quota, credentials, and audit state consistent. Extraction becomes useful only when measured load or team ownership demands it.

## Node agent instead of direct panel access

The node agent limits the control plane's knowledge of 3x-ui and keeps privileged panel credentials local. It is a replaceable adapter: another Xray management implementation can satisfy the same provisioning contract without changing Android or business policy.

## Server-rendered routing

Clients receive a fully rendered configuration rather than calculating topology. This supports rapid policy changes, consistent enforcement, and simpler clients. The trade-off is that the control plane must rigorously validate generated configs and remain available for issuance.

## Traffic accounting identity

Usage belongs to the user/device lifecycle, not one transient Xray client identifier. Rotation therefore cannot reset monthly quota. Reconciliation records deltas and must handle counter resets defensively.

## Error and observability contract

Public failures use a stable code, safe message, optional structured details, and request ID. Internal exceptions are logged without leaking stack traces to clients. Request correlation is installed at the HTTP boundary so client reports can be matched to server logs.

## AI boundary

There is no runtime model integration today. If an operations assistant is added, its contract should be:

- input: redacted, structured health aggregates and documented runbook fragments;
- output: schema-constrained diagnosis and suggested action, never executable shell text;
- model configuration: environment-driven provider/model/timeouts with bounded retries;
- prompts: versioned files reviewed alongside code;
- validation: deterministic schema, allow-listed action types, confidence treated as metadata;
- evaluation: replayable incident fixtures measuring factual grounding and unsafe suggestions;
- authority: read-only by default, with explicit operator approval and typed execution;
- privacy: no credentials, VPN links, raw client identifiers, or application secrets sent to a model.

AI should reduce operator investigation time. It must not authorize users, change quotas, select security policy, or provision/revoke credentials autonomously.

## Known trade-offs

- A shared bearer token for node agents is operationally simple but mTLS and per-node credentials are stronger.
- In-process health workers are simple but require leader election or a job queue when the API scales horizontally.
- Stub mode enables local development but integration tests must also exercise the real adapter contract.
- Release Android signing is not yet production-ready; debug signing must be replaced before distribution.
