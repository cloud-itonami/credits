# ENGI protocol R1–R6

## Authority model

No server, relay, chain, Council, Safe, token contract, balance holder, or
etzhayyim process can create an ordinary EN transfer. The authoritative object
is the signed event retained by both economic parties. State is a deterministic
replay result.

## R1 — signed deterministic replay

- Canonical domain: nil, boolean, UTF-8 string, keyword, integer, vector, map.
- Maps are recursively ordered by printed EDN key. Float, set, list, symbol,
  ratio, and tagged values are invalid.
- Event id is SHA-256 over canonical economic content with protocol version 1;
  signature bytes and copied event ids are excluded.
- Evidence uses Ed25519. Transfer requires both parties. Credit lines require
  independent guarantors. Commons uses heterogeneous witnesses.
- Participant nonces are contiguous from 1. Unknown event kinds fail closed.

## R2 — offline convergence and recovery

- Devices retain event maps indexed by content id.
- Parents define dependency order; relay arrival order is irrelevant.
- Concurrent transfers sharing `(payer DID, nonce)` are quarantined together.
  The protocol does not choose a winner.
- Device key recovery requires a predeclared guardian threshold. Rotations are
  monotonic; old public keys are retained as revoked history.

## R3 — storage, relay, checkpoint

- Participant journal: canonical EDN line log, append-only, flush + JVM fsync.
- Relays are replaceable content stores. They cannot order, mutate, mint, or
  resolve.
- Regional checkpoints require a validator threshold and are accepted only
  when state root and ordered event ids match local full replay.

## R4 — standing, Kisha, Commons adjudication

- Standing gates Commons/Kisha only; ordinary exchange remains permissionless.
- At least two independent issuer roles and an epoch nullifier are required.
- Basic Kisha divides the available pool equally. No Phenotype, reputation,
  wealth, or contribution score is accepted.
- Challenged Commons proposals use a role-diverse 5-of-7 jury.
- Appeal uses an independent role-diverse 6-of-9 jury and supersedes history.

## R5 — regions and migration

- Living-basket indices require three independently named sources per region.
- Region conversion exposes integer rounding remainder.
- Multilateral netting conserves value: regional positions always sum to zero.
- Legacy balances migrate only with participant consent, initially have zero
  monetary effect, and can become EN only through bounded Commons review.
- The legacy central actor is disabled and does not advertise graph writes.

## R6 — independent verification and audit

- `clients/engi-verify.mjs` independently implements canonical event ids and
  Ed25519 verification using only Node.js standard libraries.
- Cross-client vectors are tested against the Clojure implementation.
- The audit checks conservation, unique ids/nonces, event content ids, and
  legacy central-writer shutdown.
- Corrupt journal lines, missing parents, event-id collisions, tampering,
  unknown event kinds, stale key recovery, insufficient juries, and concurrent
  spends are fail-closed test cases.
- Verified benchmark on 2026-07-23: 100,000 deterministic transfer transitions,
  valid final state, approximately 204,665 transitions/second on the local JVM.
  Throughput is observational, not a protocol guarantee.

## Honest production boundary

The protocol implementation is production-shaped, but public launch additionally
requires a physical multi-device pilot, WebAuthn adapter, independently operated
network endpoints, mobile secure-key integration, external security review,
participant consent for any legacy export, and jurisdiction-specific legal/tax
disclosure. None of those may be represented as complete merely because
simulation tests pass.

## Production adapters

- `credits.engi.transport` exposes only immutable event publish/fetch over EDN
  HTTP. Any participant can gossip a content union between independently
  operated relays; unreachable relays do not become ordering authorities.
  Operators may attach an append-only verified cache journal, which is restored
  after process restart without becoming a source of balances or event order.
- `credits.engi.atproto` maps an event to
  `com.etzhayyim.engi.event`, using the content hash as the record key. Records
  are decoded canonically and their ENGI id is reverified after retrieval.
- The adapter tests start two real loopback HTTP services, distribute different
  objects, tolerate an unreachable third service, gossip the union, restart a
  durable relay, and verify convergence and tamper rejection.

Public launch still requires independently hosted relay endpoints and a live PDS
create/list roundtrip; local socket integration does not prove either external
operator independence or external service availability.
