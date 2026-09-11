# etzhayyim-project-credits — Credit Ledger & Public Fund Routing

> **SUPERSEDED FOR NEW VALUE FLOWS (2026-07-23):** The centrally issued
> purchase/earn/spend ledger and GCC minter described below are legacy migration
> inputs, not the canonical monetary design. New value flows use the ENGI mutual-
> credit kernel in `README.md` + `src/credits/methods/engi.cljk`: bilateral issuance, relational
> credit limits, bounded heterogeneous Commons issuance, no admin mint, and no
> balance-weighted governance. USDC/GCC/Kisha are adapters during migration only.

**URL**: `https://credits.etzhayyim.com`

## Architecture

Credits は yoro.etzhayyim.com の human participation 課金システム。Earn (compute/HC) → Purchase → Spend のクレジットサイクルを管理する。

2026-03-30 時点の標準ポリシー:

- credits 購入時は 30% を platform fee として控除
- credits 消費時は 10% を `etzhayyim-project-public-fund` に自動分配
- 10% の分配先は user が `credits` UI で選択可能
- 分配先未指定時は `public-fund:common` を使用

| Component | nanoid | 役割 |
|---|---|---|
| **credits-mcp** | `credits-mcp` | Credit ledger, transactions, anti-fraud, purchase fee, public-fund routing |
| **GCC Wallet** | `wt1e2f3g` | Ethereum ERC-20 (GCC token), HD wallet, Chainlink price |

## Commands (credits-mcp)

### Queries
| Command | 入力 | 出力 | 用途 |
|---|---|---|---|
| `GetBalance` | `user_id` | `{balance, user_id}` | クレジット残高取得 |
| `ListTransactions` | `user_id, limit, offset` | `{transactions[], total}` | 取引履歴 |
| `GetDefaultPlan` | — | `{default_plan}` | 購入 fee / 分配 policy の参照 |
| `CheckSpendAllowed` | `user_id, action, amount?` | `{allowed, reason?, balance, cost}` | 消費可否 |
| `GetAllocationOptions` | — | `{options[], default_destination_id}` | public fund 分配先候補 |
| `GetAllocationPreference` | `user_id` | `{preference}` | user の分配先 |
| `PreviewPurchase` | `gross_amount` | `{purchase}` | 30% fee 控除見積 |
| `PreviewSpend` | `user_id?, action, amount?, destination_id?` | `{spend, destination}` | 10% 分配見積 |

### Mutations
| Command | 入力 | 出力 | 用途 |
|---|---|---|---|
| `EarnCredits` | `user_id, amount, source, description` | `{balance, tx_id}` | 任意 source から付与 |
| `PurchaseCredits` | `user_id, gross_amount, source?, destination_id?` | `{balance, tx_id, purchase}` | 30% fee 控除購入 |
| `SpendCredits` | `user_id, amount?, action, destination_id?` | `{balance, tx_id, allocation, destination}` | 10% 分配付き消費 |
| `RewardFromHC` | `user_id, task_id, contribution_type, amount?, approval_rate` | `{balance, tx_id, amount}` | HC 報酬 |
| `RewardFromCompute` | `user_id, session_id, jobs_done, gpu_time_ms, amount?, source` | `{balance, tx_id, amount}` | compute 報酬 |
| `SetAllocationPreference` | `user_id, destination_id` | `{preference}` | 分配先の保存 |

## Purchase / Allocation Policy

| Flow | Rule |
|---|---|
| Credits purchase | 30% を控除し、70% を wallet に計上 |
| Credits spend | 消費額の 10% を public fund に自動分配 |
| Distribution target | user が `credits` ページで選択 |
| Default target | `public-fund:common` |

### Allocation Destinations

| destination_id | Label | Role |
|---|---|---|
| `public-fund:common` | Common Fund | デフォルトの共通 fund |
| `public-fund:education-family` | Education & Family Fund | 教育・子育て向け |
| `public-fund:health-access` | Health Access Fund | 医療アクセス向け |
| `public-fund:climate-resilience` | Climate Resilience Fund | 防災・環境向け |

## Credit Rates

### Earn

| Source | Rate |
|---|---|
| HC translation | ¥3 |
| HC code review | ¥5 |
| HC micro task | ¥2 |
| HC moderation | ¥1 |
| HC survey | ¥0.5 |
| Murakumo per job | ¥0.1 |
| Murakumo GPU per min | ¥0.3 |

### Spend

| Action | Cost |
|---|---|
| Post | ¥1 |
| Reply | ¥0.5 |
| DM | ¥0.5 |
| MCP invoke (base) | ¥0.5 |
| MCP invoke (per 1KB request payload) | ¥0.1 |
| MCP invoke (per 1KB response payload) | ¥0.1 |

**MCP invoke** action は host-sdk `dispatchMcp` (`kotoba-lang/kotodama-host/sdk/kotodama-host-sdk/src/mcp-server.ts`) の `tools/call` 経路から `SpendCredits({user_id, action: "mcp_invoke", amount, metadata: {tool_nsid, actor_did}})` で発火 (ADR-2604271400)。10% public fund 再分配は既存 `SpendCredits` allocation 経路で自動継承され、新規分配ロジックは導入しない。

## Anti-Fraud

| 対策 | 閾値 |
|---|---|
| Spend rate limit | 60 回/hour |
| Earn rate limit | 30 回/hour |
| High-value earn reject | > 50 credits |
| HC reputation gate | `approval_rate < 50%` |
| Duplicate reward | 同一 task/session を拒否 |

## Svelte Demo Console

`wasm/credits-mcp-component/svelte` は policy / routing の preview 用 console。

| Route | Method | 用途 |
|---|---|---|
| `/api/plans` | `GET` | purchase / allocation policy を返す |
| `/api/balance/{userId}` | `GET` | demo balance を返す |

## Integration

### yoro.etzhayyim.com → credits-mcp

```txt
Header Credits button → /credits
Post/Reply/DM → CheckSpendAllowed → SpendCredits
Credits page → SetAllocationPreference / PreviewPurchase / PreviewSpend
```

### hc.etzhayyim.com → credits-mcp

```txt
approve-assignment → RewardFromHC
```

### murakumo.etzhayyim.com → credits-mcp

```txt
Browser session end → RewardFromCompute
Inference complete  → SpendCredits({action:"inference", ...})
```

### kotodama-host-sdk `/mcp` → credits-mcp (ADR-2604271400)

```txt
tools/call pre  → CheckSpendAllowed({action:"mcp_invoke", payloadBytes})
tools/call post → SpendCredits({action:"mcp_invoke", amount, metadata:{tool_nsid, actor_did}})
```

## Data Model

| Record | Key | Fields |
|---|---|---|
| `credit_wallet` | user_id | balance |
| `credit_transaction` | tx_id | type, amount, source, description |
| `af_event` | ts | earn/spend anti-fraud event |
| `allocation_preference` | user_id | destination_id, title, allocation_bps |
| `purchase_settlement` | tx_id | gross_amount, fee_amount, net_credits |
| `public_fund_allocation` | allocation_id | spend_tx_id, public_fund_amount, destination_id |

## GCC Token (Ethereum)

| Item | Address |
|---|---|
| GCC Token | `0x799d24a6FFBb758C6E2Ed8f981822A17Eaa5F30B` |
| GCC Minter | `0xAf80b152eD85067F8386416767b9658E86C253d9` |
| Safe Treasury | `0xA00366234D29d4F882088048c0B2fa0dB7302D4E` |
| Chainlink ETH/USD | `0x5f4eC3Df9cbd43714FE2740f5E3616155c5b8419` |

Design: `TOKEN_DESIGN_ETHEREUM.md`
