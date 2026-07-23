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
- [x] Durable canonical append-only file journal (flush/fsync) per participant.
- [x] Replaceable content relays; relay order has no authority.
- [x] Threshold-signed regional checkpoint verified against full replay.
- [x] Plural standing issuer roles + epoch nullifier duplicate guard.
- [x] Equal basic Kisha allocation with no Phenotype/reputation input.
- [x] Commons proposal/challenge/5-of-7 jury/independent 6-of-9 appeal.
- [ ] kotoba/AT adapter over the durable journal file.
- [ ] Network gossip transport over the replaceable relay interface.
- [ ] Physical offline three-device field pilot; no etzhayyim server in path.
- [x] Disable legacy actor and remove advertised `graph.write` capability.
- [x] Legacy balance export is opt-in evidence with zero automatic EN effect.
- [x] Route an accepted legacy claim through bounded Commons deliberation.
- [x] Source-diverse living-basket index + explicit-rounding region conversion.
- [x] Multilateral regional netting with zero-sum conservation.
- [x] Make basic Kisha equal Commons allocation; no Phenotype input.
- [ ] Export/reconcile real legacy entries and collect participant consent.
- [ ] Remove dormant legacy mutation pipelines after the reconciliation window.

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
