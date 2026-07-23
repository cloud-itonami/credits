# Migration TODO

**Status**: 🔄 TRANSFORM — ENGI R0 social kernel landed 2026-07-23;
central-ledger retirement and device integration pending.

**Target**: central credit/GCC → participant-owned ENGI journals. USDC and
TitheRouter are optional boundary adapters, not the monetary source of truth.

## ENGI integration gates

- [x] Pure replayable mutual-credit state machine.
- [x] Bilateral signatures required at the state-machine boundary.
- [x] Relational credit limit from independently signed endorsements.
- [x] Replay protection and zero-net-supply invariant.
- [x] Bounded Commons issuance with heterogeneous role quorum.
- [x] Canonical event encoding + content hash test vectors (Clojure R1).
- [x] Ed25519 verifier + device key envelope (WebAuthn adapter remains R2).
- [x] Full untrusted-journal replay + deterministic state root.
- [x] Contiguous participant nonce and fail-closed event dispatch.
- [x] In-memory participant/device journal with deterministic dependency merge.
- [x] Three-device offline convergence simulation; relay order has no authority.
- [x] Concurrent offline-spend quarantine (never arrival-time winner selection).
- [x] Guardian-threshold device-key recovery and replay protection.
- [ ] Durable append-only kotoba/AT journal owned by each participant.
- [ ] Replaceable gossip relay transport and signed regional checkpoint.
- [ ] Physical offline three-device field pilot; no etzhayyim server in path.
- [ ] Retire legacy graph.write earn/spend pipelines after export/reconciliation.
- [ ] Make Kisha a Commons issuance policy; remove Phenotype from basic provision.

## Substrate-boundary checks

This actor SDK was copied verbatim from `etzhayyim-root/20-actors/credits`.
Following must be remediated:

- [ ] Replace direct `@atproto/api` / `viem` / IPFS / Signal client imports with `@etzhayyim/sdk`.
- [ ] Strip RisingWave / Postgres / Kysely → AT MST + IPFS + Base L2 anchor.
- [ ] Strip Stripe / PayPal / fiat → USDC + ERC-4337 + `etzhayyim-tithe-router`.
- [ ] Remove 3rd-party ad / GA4 / Meta Pixel.
- [ ] DID-bind authentication (did:web:etzhayyim.com + did:plc + WebAuthn + Adherent SBT).
- [ ] Verify against Charter Rider v2.0 §2(a)-(h).

## Reference

- ADR-2605192100 / 2605192115 / 2605192200
- `/CLAUDE.md` § Substrate boundary
