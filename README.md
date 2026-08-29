# ENGI — decentralised mutual-credit substrate

## FEVM/EVM bridge prototype

`contracts/WrappedEN.sol` is an executable ERC-20 representation of positive EN locked in a dedicated ENGI bridge account. It does not replace ENGI, cannot represent negative balances and cannot mint past an independently reported locked-EN reserve. See `docs/260830-engi-wen-fevm-bridge.md` and run `./scripts/verify-fevm-compat.sh`.

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

## Valueflows projection (read-only)

`credits.engi.valueflows` projects accepted events onto the workspace's shared
economic vocabulary ([Valueflows](https://www.valueflo.ws/), ADR-2608153000), so
a signed EN transfer can be queried alongside a production order or an hour of
work instead of living on its own island.

| ENGI | vf action | why |
|---|---|---|
| transfer | `transfer` | accounting and onhand both decrement/increment — exactly zero net supply |
| Commons issuance | `raise` | increments one balance with no counterparty |
| credit line | *none* | a standing willingness to hold a negative balance is not a flow |

**Projection is not admission.** Nothing there verifies a signature, a nonce, a
credit limit or an epoch cap — those are the kernel's, and its constitutional
boundary is untouched. The projection only reads events the kernel already
accepted, and writes nothing. A test pins this: an unsigned transfer still
projects, with no signers recorded, so the absence is visible rather than
filled in.

Amounts stay **integer micro-EN**. Dividing by a million to present EN would
turn an exact integer into a fraction, and integer exactness is what the
zero-net-supply invariant rests on. `:micro-en` is registered as
`:quantity-kind :mutual-credit`, not `:currency`, so nothing adds it to yen.

`net-supply` carries the constitutional invariant across the join: it replays
the *projected* events through `valueflows.event` and checks that transfers left
the total unchanged and only Commons issuance raised it. If a projection ever
broke that, the vocabulary layer would be telling a different story about the
same ledger than the kernel does.

`:type` is authoritative — `credits.engi.replay/apply-event` dispatches on it and
rejects anything else — with shape as the fallback for untagged events. A
declared type the record cannot support (`:type :transfer` with no `:from`) is a
reported conflict, not a projected flow with no payer.

### A committed corpus, and a ratchet on the mapping

`resources/engi/example-journal.edn` is a small **synthetic** society: seven
events, every one accepted by the real kernel under real Ed25519 verification
and a full replay, replaying to a recorded state root with balances
`{alice -35, bob 35, carer 30, carol 0}` — summing to exactly the 30 of Commons
issuance. It is a fixture, not a record of anyone's activity, and the file says
so in its own header.

`resources/engi/example-journal.valueflows.edn` is its projection, regenerated
and compared on every test run. If the mapping drifts, the suite fails and says
what to run. Regenerate deliberately with:

```bash
clojure -M:dev -m credits.dev.generate-example-journal   # writes both files
clojure -M:test -n credits.engi-valueflows-test          # 19 tests / 85 assertions
```

The generator refuses to write anything if the kernel rejects the society it
built, so a corpus can never be committed that the kernel would not accept.

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

R1 adds canonical encoding, content-derived ids, real Ed25519 evidence, and full
untrusted replay. R2 adds participant/device journals, dependency-ordered merge,
three-device convergence, explicit quarantine of concurrent offline spends, and
guardian-threshold device-key recovery. A relay's arrival order never selects a
winning spend.

R3 persists participant journals with append/flush/fsync, supports replaceable
content relays, and verifies threshold-signed regional checkpoints against a full
replay. R4 adds plural standing credentials with epoch nullifiers, equal basic
Kisha with no Phenotype input, and challenged Commons decisions with independent
appeal juries.

R5 adds source-diverse regional living-basket indices, explicit-rounding
cross-region conversion, conservative multilateral netting, and opt-in legacy
claims. A legacy credit balance has zero monetary effect until Commons review;
the disabled legacy actor no longer advertises `graph.write`. Consent is a
participant-signed event bound to the exported ledger root, not an
administrator-set boolean.

R6 adds an independent dependency-free Node.js verifier, cross-client canonical
and Ed25519 vectors, protocol audits, corrupt/Byzantine input rejection, and a
100,000-transition JVM benchmark. See [`PROTOCOL.md`](PROTOCOL.md).

## Run an independent relay

Each operator chooses their own relay id, storage path, host, and port:

```bash
ENGI_RELAY_ID=neighbourhood-a \
ENGI_RELAY_HOST=127.0.0.1 \
ENGI_RELAY_PORT=8080 \
ENGI_RELAY_JOURNAL=/var/lib/engi/events.edn \
clojure -M:relay
```

`GET /healthz`, `GET /v1/events`, and `POST /v1/events` are the only service
surfaces. The process stores verified immutable events and can gossip their
content union; it has no balance, mint, ordering, freeze, or resolution API.
Internet-facing operators terminate TLS independently in front of this process.
Participant clients call `credits.engi.sync/sync-device!` to merge relay
contents into their own durable journal; relay arrival order is never copied as
ledger order. Fetches are paginated and publishes are bounded batches, while
clients still reconstruct the complete content set before dependency merge.

## Integration direction

1. Each participant stores their signed events in their own append-only kotoba/AT
   journal; counterparties retain the same event.
2. Devices gossip events directly or through any number of replaceable relays.
3. Regional cells periodically publish Merkle roots and netting proposals.
4. Independent chains may anchor roots, but no chain, Safe, Council, or etzhayyim
   service can create EN.
5. Existing USDC/Kisha/GCC paths remain migration adapters only. They are not the
   ENGI source of truth.

The implementation does not pretend to provide physical enforcement, legal tender
status, or Sybil-proof personhood. The remaining production gates are live
participant-controlled PDS and authenticator ceremonies, independently hosted
relays, a physical three-device pilot, consent-based legacy reconciliation, and
independent security/legal review.
