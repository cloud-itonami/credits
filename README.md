# ENGI — decentralised mutual-credit substrate

ENGI replaces the centrally issued GCC/credit model. `EN` is an integer
accounting unit created between participants when a real exchange is signed by
both sides. It is not an ERC-20, is not purchased from etzhayyim, has no owner or
administrator, pays no interest, and grants no governance, land, membership, or
Kisha rights.

## Constitutional boundary

- No privileged mint key, treasury mint, admin balance edit, freeze, or blocklist.
- A normal transfer creates an equal debit and credit; net supply stays zero.
- Both parties sign the same event. `(DID, nonce)` can be consumed once.
- Signed transfer, endorsement, and Commons quorum evidence remains in the
  accepted journal so a new client can verify state without trusting a snapshot.
- Negative balance is bounded by independent relational endorsements, not stake.
- Commons issuance is distinct from trade and requires at least four distinct
  witnesses spanning local-community, independent-witness, and commons-guardian
  roles, plus an explicit epoch cap.
- State is accepted by deterministic replay. A checkpoint or chain anchor is
  evidence of a state, never authority to rewrite it.
- EN balance never determines protocol votes.

## Runnable social kernel

[`src/credits/methods/engi.cljc`](src/credits/methods/engi.cljc) is a portable pure state machine. It
implements relational credit lines, two-party transfers, nonce replay protection,
bounded heterogeneous Commons issuance, and replay invariants. Cryptographic
verification is injected at the boundary, allowing Ed25519/passkey implementations
on devices without making a server authoritative.

```bash
bb run_tests.clj
```

The tests instantiate a small society: participants endorse one another, exchange
EN, repay in the opposite direction, and recognise care work through a bounded
multi-role Commons decision. Every client can replay the same event set and reject
a checkpoint where mutual-credit net supply is non-zero or a participant exceeds
their relational credit limit.

## Integration direction

1. Each participant stores their signed events in their own append-only kotoba/AT
   journal; counterparties retain the same event.
2. Devices gossip events directly or through any number of replaceable relays.
3. Regional cells periodically publish Merkle roots and netting proposals.
4. Independent chains may anchor roots, but no chain, Safe, Council, or etzhayyim
   service can create EN.
5. Existing USDC/Kisha/GCC paths remain migration adapters only. They are not the
   ENGI source of truth.

R0 deliberately does not pretend to provide physical enforcement, legal tender
status, Sybil-proof personhood, or production key recovery. The next integration
gate is a real Ed25519/passkey envelope and kotoba journal adapter, followed by an
offline three-device field pilot.
