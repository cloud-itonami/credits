# Chain-independent ENGI settlement architecture

Status: accepted design boundary; FEVM adapter implemented as a Calibration prototype.

## Decision

ENGI is the economic system of record. EVM, FEVM, Holochain, AT Protocol, SQL and files are transports, indexes or settlement projections. None may become an admission authority for ENGI balances.

The design borrows Holochain's useful agent-centric properties without making Holochain a runtime dependency: each participant authors signed append-only activity; records name their prior dependencies; deterministic peer validation admits or rejects them; content-addressed evidence can be replicated over more than one network. ENGI's bilateral signatures, credit-line rules, nonce continuity and full replay remain the actual rules of the game.

```text
agent signed journals ──> deterministic ENGI validation/replay ──> ENGI state root
        │                              │                              │
        ├─ local/ATProto/Holochain DHT ┘                              │
        │                                                             │
        └──────────────── public evidence graph <──── projection claims┘
                                                               │
                                        ┌──────────────────────┼───────────────┐
                                        ▼                      ▼               ▼
                                  FEVM/EVM wEN             future chain     accounting/index
                                  adapter only              adapter          adapters
```

## Dependency rule

The arrow always points outward:

```text
credits.methods.engi + credits.engi.replay
  <- credits.engi.settlement (chain-neutral claims)
     <- FEVM/EVM encoder, relayer and WrappedEN contract
```

The kernel must not import Solidity ABI types, chain ids, transaction hashes, gas rules, RPC clients or Holochain conductor APIs. A chain outage, fork, fee-market change or migration must not prevent ENGI participants from signing, exchanging, merging journals and reproducing balances.

## Claim boundary

`credits.engi.settlement` creates content-addressed read-only claims from already accepted ENGI evidence:

- reserve: checkpoint root, sequence, bridge DID and locked micro-EN;
- deposit: canonical transfer into the bridge DID plus checkpoint and amount;
- release: canonical transfer out of the bridge DID plus an opaque adapter withdrawal reference.

Claims are not admitted by the ENGI replay kernel and cannot change an ENGI balance. Each adapter chooses its own encoding. The FEVM adapter maps ENGI ids to `bytes32`, attaches chain/contract-bound EIP-712 signatures and submits them to `WrappedEN`. Another adapter can use a different VM, signature suite or storage network without changing ENGI.

## Holochain-inspired replication

- An agent journal is append-only and tamper-evident.
- Events explicitly reference the credit-line and prior nonce events they depend on.
- Validation is deterministic: the same event plus the same addressed dependencies yields the same result.
- Peers may store and validate shards of public evidence; private entry data can remain with an agent while public actions and hashes are replicated.
- Conflicting histories are evidence of equivocation, not a reason to let arrival order choose a winner.
- Regional checkpoints accelerate reads but never replace full event replay.

This can be implemented using a Holochain DNA later, but the ENGI event codec and replay vectors must remain independently executable by Clojure, Node and other clients.

## Settlement safety

1. A bridge adapter observes a canonical ENGI transfer into a dedicated bridge DID.
2. Independent replay nodes reproduce the state root and locked balance.
3. The reserve committee attests the checkpoint; the bridge committee attests the deposit claim.
4. wEN is minted only within the attested reserve.
5. Withdrawal burns wEN before any ENGI release.
6. A new bilateral ENGI transfer releases the value; only then may the bridge committee finalize the external withdrawal receipt.

External receipts are observations, not ENGI state transitions. If FEVM disappears, unprocessed ENGI remains valid; if an adapter is replaced, replayable ENGI claims can be projected to the replacement under an explicit migration policy.

## Production gates

- Bind every withdrawal attestation to release event parties, amount and recipient, not only an opaque event id.
- Add a portable claim schema and independent encoder test vectors for every adapter.
- Run replay/validation on separately operated peers and publish validation receipts.
- Specify fork/equivocation warrants and recovery without global arrival ordering.
- Require audited migration and supply reconciliation before moving value between adapters.
