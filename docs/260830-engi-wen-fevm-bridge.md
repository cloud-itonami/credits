# ENGI / EN to FEVM ERC-20 bridge

Status: executable prototype; not deployed; not production-authorized.

## Decision

ENGI remains the canonical signed bilateral mutual-credit ledger. It permits bounded negative balances and preserves zero net mutual-credit supply, except separately disclosed Commons issuance. Those semantics cannot be represented faithfully by ERC-20's unsigned non-negative balances.

`WrappedEN` (`wEN`) therefore represents only positive EN transferred into a dedicated bridge DID. It is not EN, cannot create EN and cannot import a negative EN balance.

## Conservation boundary

```text
wEN.totalSupply <= independently replayed positive EN balance locked at bridge DID
```

An ENGI deposit event is accepted once. Its event id becomes `depositId`; the replayed checkpoint becomes `checkpointRoot`. Minting is rejected if the deposit was processed, the checkpoint is stale, or the mint would exceed reported locked EN.

Withdrawal burns wEN first and creates a deterministic request. The bridge later attaches the canonical bilateral ENGI transfer event that releases EN from the bridge DID. Burning does not itself prove EN settlement; `WithdrawalFinalized` records that second boundary explicitly.

## Roles

- `admin`: pause/unpause and two-step role rotation; cannot mint.
- `bridge`: bind one canonical ENGI deposit to one mint and finalize withdrawal evidence.
- `reserveOracle`: publish independently replayed checkpoint roots and locked bridge balance.

The prototype uses single addresses for the bridge and reserve reporter so behavior can be exercised locally. Production requires independent threshold signers, hardware-backed keys, delayed role changes, incident recovery and continuous supply/reserve reconciliation. Bridge and reserve authority must not be the same signer set.

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

No private key is stored in this repository. After independent review and funding a test-only address with tFIL:

```bash
forge create contracts/WrappedEN.sol:WrappedEN \
  --rpc-url https://api.calibration.node.glif.io/rpc/v1 \
  --chain-id 314159 \
  --private-key "$WEN_CALIBRATION_DEPLOYER_KEY" \
  --constructor-args "$ADMIN" "$BRIDGE" "$RESERVE_ORACLE" \
  --broadcast
```

Deployment is not bridge activation. Minting must remain paused operationally until the ENGI bridge DID, independent replay service, checkpoint policy, signer separation, monitoring, legal classification and unwind procedure exist.

## Compatibility result

| Layer | Result | Boundary |
|---|---|---|
| ERC-20 wallets/DEX ABI | compatible | standard methods/events and 6 decimals |
| FEVM bytecode | compatible by local build/opcode gate | live Calibration deploy still required |
| ENGI positive escrow | structurally compatible | reserve oracle is trusted in prototype |
| ENGI negative balances | intentionally incompatible | cannot be represented by ERC-20 |
| ENGI Ed25519 proof | not verified on-chain | bridge currently submits opaque event/checkpoint ids |
| Uniswap-style AMM | ABI-compatible | pool deployment, price/risk and legal gates are separate |
| Filecoin storage proofs | adapter-ready | no storage-deal verification in token core |

## Before real value

1. Specify bridge-DID signing policy and prevent any non-burn-backed withdrawal.
2. Replace single bridge/oracle roles with independent threshold attestations and timelocks.
3. Verify ENGI Ed25519-to-FEVM evidence or publish a formally scoped ECDSA committee attestation.
4. Add continuous `totalSupply <= locked EN` monitoring with automatic pause and public receipts.
5. Audit Solidity, replay/finality assumptions, role recovery and frontend approval handling.
6. Obtain jurisdiction-specific treatment for transferable wEN and any LP/revenue rights.
7. Deploy first to a local EVM, then Filecoin Calibration; do not deploy mainnet from this prototype.
