# ENGI / EN to FEVM ERC-20 bridge

Status: executable threshold-attestation prototype; Calibration synthetic round trip verified; not production-authorized.

## Decision

ENGI remains the canonical signed bilateral mutual-credit ledger. It permits bounded negative balances and preserves zero net mutual-credit supply, except separately disclosed Commons issuance. Those semantics cannot be represented faithfully by ERC-20's unsigned non-negative balances.

`WrappedEN` (`wEN`) therefore represents only positive EN transferred into a dedicated bridge DID. It is not EN, cannot create EN and cannot import a negative EN balance.

The dependency direction and Holochain-inspired agent-centric replication model are specified in `docs/260830-chain-independent-settlement.md`. FEVM/EVM is an optional settlement projection and is never required for ENGI admission or replay.

## Conservation boundary

```text
wEN.totalSupply <= independently replayed positive EN balance locked at bridge DID
```

An ENGI deposit event is accepted once. Its event id becomes `depositId`; the replayed checkpoint becomes `checkpointRoot`. Minting is rejected if the deposit was processed, the checkpoint is stale, or the mint would exceed reported locked EN.

Withdrawal burns wEN first and creates a deterministic request. The bridge later attaches the canonical bilateral ENGI transfer event that releases EN from the bridge DID. Burning does not itself prove EN settlement; `WithdrawalFinalized` records that second boundary explicitly.

## Authorities

- `admin`: pause/unpause and two-step admin rotation; cannot mint.
- `bridge committee` (2-of-3 on Calibration): bind one canonical ENGI deposit to one mint and finalize withdrawal evidence.
- `reserve committee` (2-of-3 on Calibration): publish independently replayed checkpoint roots and locked bridge balance.

The bridge and reserve committees are disjoint and immutable for this deployment. Attestations use an EIP-712 domain bound to the chain id and contract address, include an expiry, and require recovered signer addresses in strictly increasing order. A duplicate, unsorted, non-member, high-`s`, malformed or expired signature is rejected. Committee replacement deliberately requires a reviewed migration to a new contract; production additionally requires hardware-backed keys, signer organizations in separate failure domains, delayed migration, incident recovery and continuous supply/reserve reconciliation.

## Cloud Itonami liquidity integration

The integrated design has three fungible roles and one receipt. They must remain separate:

| Asset | Function | Must not mean |
|---|---|---|
| `wEN` | positive EN settlement imported from ENGI escrow | ENGI negative credit, new EN issuance, or a claim on Cloud Itonami |
| `BOT` | protocol fee, objective service bond, dispute bond and parameter governance | guaranteed yield or ownership of all data |
| `DAC(product, epoch)` | expiring licensed data/query capacity | ownership of raw data or permanent access |
| `DLP` | permissioned liquidity-position receipt | an unregulated public profit share |

The consumption pool is `wEN / DAC`, because its price is the amount of settled positive EN paid for deliverable data capacity. A separate `wEN / BOT` pool may provide protocol-token liquidity. A direct `EN / anything` AMM does not exist: ENGI negative balances and relational credit lines remain off-chain canonical state.

```text
ENGI bilateral transfer -> bridge DID escrow -> mint wEN
wEN <-> DAC(product, epoch) -> query fulfilled -> DAC burned
LP deposits wEN + DAC capacity -> DLP receipt -> realized usage fees
wEN burn -> ENGI bilateral release -> withdrawal finalized
```

An industry layer such as ISIC is a pool registry/index. It does not mint one speculative token for every company. Data Product license, capacity and provenance remain Hyakka claims; wEN settlement remains this bridge; AMM reserves and DLP positions remain separate economic state.

## FVM/FEVM compatibility

The contract is Solidity 0.8.24 bytecode with the standard ERC-20 ABI. It uses no `CALLCODE`, `SELFDESTRUCT`, native FIL transfer, `BLOCKHASH`, fixed gas stipend, Filecoin precompile or chain-specific storage API. It should execute unchanged on FEVM and ordinary Shanghai-compatible EVMs.

Filecoin-specific integration remains outside the token contract:

- use `f410`/`t410` delegated addresses corresponding to `0x` addresses;
- send calls through Ethereum JSON-RPC / InvokeEVM;
- estimate Filecoin gas at execution time and never hard-code Ethereum gas assumptions;
- index receipts one epoch later under Filecoin's deferred execution model;
- optionally bind Hyakka/ENGI evidence CIDs to Filecoin storage-deal attestations in a separate adapter.

## Local verification

```bash
./scripts/verify-fevm-compat.sh
```

The gate builds the contract, executes lifecycle and adversarial tests, confirms standard ERC-20 selectors and rejects FEVM-sensitive `CALLCODE`/`SELFDESTRUCT` opcodes.

## Calibration deployment shape

After independent review and funding the test-only address with tFIL, use the public committee configuration in `deployments/filecoin-calibration.json`. No private key is stored in this repository. The deployment script reads test-only keys from macOS Keychain:

```bash
./scripts/wen-calibration.sh deploy
./scripts/wen-calibration.sh roundtrip
```

The round trip uses the repository's cryptographically replayed synthetic ENGI corpus root and a test-only positive balance solely to prove checkpoint attestation, mint, burn and settlement-finalization plumbing. It is not evidence of production EN escrow or anyone's economic activity.

Deployment is not bridge activation. Mainnet minting must remain disabled until the real ENGI bridge DID, independent replay services, checkpoint/finality policy, organizational signer separation, monitoring, legal classification, migration and unwind procedures exist.

### Verified Calibration result

- Contract: `0xbf9e553d406f4ea85fb80cf3636b9c97b5403030`
- Deployment transaction: `0x465d6a1ddd9bb06d8762e6db8f51d3f6dff781b18a2b3af68bbb314c1b2a4356`
- Deployment block: `4024112`
- Committees: disjoint 2-of-3 bridge and 2-of-3 reserve
- Replayed checkpoint reserve: 35 micro-EN; test mint/burn: 10 micro-EN
- Finalization block: `4024121`
- Readback: `totalSupply = 0`, deployer wEN balance `= 0`, `solvent = true`

All five receipts (deployment, reserve, mint, withdrawal and finalize) returned success. Exact transaction ids and the synthetic-evidence limitation are recorded in `deployments/filecoin-calibration.json`.

## Compatibility result

| Layer | Result | Boundary |
|---|---|---|
| ERC-20 wallets/DEX ABI | compatible | standard methods/events and 6 decimals |
| FEVM bytecode | compatible and deployed on Calibration | production review and mainnet deployment still required |
| ENGI positive escrow | structurally compatible | Calibration round trip uses synthetic replay evidence, not production escrow |
| ENGI negative balances | intentionally incompatible | cannot be represented by ERC-20 |
| ENGI Ed25519 proof | replayed off-chain, not verified on-chain | two independent ECDSA threshold committees attest scoped evidence |
| Uniswap-style AMM | ABI-compatible | pool deployment, price/risk and legal gates are separate |
| Filecoin storage proofs | adapter-ready | no storage-deal verification in token core |

## Before real value

1. Specify bridge-DID signing policy and prevent any non-burn-backed withdrawal.
2. Place committee keys in separate organizations and hardware-backed signers; specify migration timelocks.
3. Formally specify and audit the ENGI Ed25519 replay-to-ECDSA threshold attestation boundary.
4. Add continuous `totalSupply <= locked EN` monitoring with automatic pause and public receipts.
5. Audit Solidity, replay/finality assumptions, role recovery and frontend approval handling.
6. Obtain jurisdiction-specific treatment for transferable wEN and any LP/revenue rights.
7. Deploy first to a local EVM, then Filecoin Calibration; do not deploy mainnet from this prototype.
