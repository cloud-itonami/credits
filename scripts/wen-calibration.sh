#!/usr/bin/env bash
set -euo pipefail

rpc_url="https://api.calibration.node.glif.io/rpc/v1"
chain_id="314159"
manifest="deployments/filecoin-calibration.json"
deployer="$(jq -r '.deployer' "${manifest}")"
deployer_key_service="gftd.etzhayyim.deployer"
deployer_key_account="BASE_SEPOLIA_PRIVATE_KEY"
deployer_op_item="dnhen55xpdreg6q4q67x5w7uwq"
committee_key_service="gftd.cloud-itonami.wen.calibration"

die() {
  echo "REFUSED: $*" >&2
  exit 1
}

keychain_secret() {
  security find-generic-password -s "$1" -a "$2" -w 2>/dev/null
}

require_network() {
  local actual_chain
  actual_chain="$(cast chain-id --rpc-url "${rpc_url}")"
  [[ "${actual_chain}" == "${chain_id}" ]] || die "unexpected chain id ${actual_chain}"
}

deployer_key() {
  local key actual
  key="$(keychain_secret "${deployer_key_service}" "${deployer_key_account}")" || true
  if [[ -z "${key}" ]]; then
    key="$(op item get "${deployer_op_item}" --fields credential --reveal 2>/dev/null)" ||
      die "testnet deployer key is unavailable in Keychain and 1Password"
  fi
  actual="$(cast wallet address --private-key "${key}")"
  [[ "${actual}" == "${deployer}" ]] || die "Keychain deployer address mismatch"
  printf '%s' "${key}"
}

sign_two() {
  local account_a="$1" account_b="$2" digest="$3"
  local key_a key_b address_a address_b lower_a lower_b sig_a sig_b
  key_a="$(keychain_secret "${committee_key_service}" "${account_a}")" ||
    die "missing Keychain signer ${account_a}"
  key_b="$(keychain_secret "${committee_key_service}" "${account_b}")" ||
    die "missing Keychain signer ${account_b}"
  address_a="$(cast wallet address --private-key "${key_a}")"
  address_b="$(cast wallet address --private-key "${key_b}")"
  sig_a="$(cast wallet sign --no-hash --private-key "${key_a}" "${digest}")"
  sig_b="$(cast wallet sign --no-hash --private-key "${key_b}" "${digest}")"
  unset key_a key_b
  lower_a="$(printf '%s' "${address_a}" | tr '[:upper:]' '[:lower:]')"
  lower_b="$(printf '%s' "${address_b}" | tr '[:upper:]' '[:lower:]')"
  if [[ "${lower_a}" < "${lower_b}" ]]; then
    SIGNATURES="[${sig_a},${sig_b}]"
  else
    SIGNATURES="[${sig_b},${sig_a}]"
  fi
}

transaction_hash() {
  jq -r '.transactionHash // .transaction_hash // empty'
}

wait_success() {
  local tx="$1" status
  status="$(cast receipt --rpc-url "${rpc_url}" --confirmations 1 --json "${tx}" | jq -r '.status')"
  [[ "${status}" == "0x1" || "${status}" == "1" ]] || die "transaction failed: ${tx}"
}

deploy() {
  local key balance output contract tx bridge_signers reserve_signers bytecode encoded creation
  require_network
  balance="$(cast balance --rpc-url "${rpc_url}" "${deployer}")"
  [[ "${balance}" != "0" ]] || die "deployer has zero tFIL"
  key="$(deployer_key)"
  bridge_signers="$(jq -r '"[" + (.bridgeCommittee.signers | join(",")) + "]"' "${manifest}")"
  reserve_signers="$(jq -r '"[" + (.reserveCommittee.signers | join(",")) + "]"' "${manifest}")"
  forge build >/dev/null
  bytecode="$(forge inspect WrappedEN bytecode)"
  encoded="$(cast abi-encode 'constructor(address,address[],uint8,address[],uint8)' \
    "${deployer}" "${bridge_signers}" 2 "${reserve_signers}" 2)"
  creation="${bytecode}${encoded#0x}"
  output="$(cast send --rpc-url "${rpc_url}" --private-key "${key}" --legacy --json \
    --create "${creation}")"
  unset key
  contract="$(printf '%s' "${output}" | jq -r '.contractAddress // .deployedTo // empty')"
  tx="$(printf '%s' "${output}" | jq -r '.transactionHash // empty')"
  [[ -n "${contract}" && -n "${tx}" ]] || die "could not parse deployment receipt"
  [[ "$(cast code --rpc-url "${rpc_url}" "${contract}")" != "0x" ]] ||
    die "deployed contract has no bytecode"
  echo "CONTRACT_ADDRESS=${contract}"
  echo "DEPLOYMENT_TX=${tx}"
}

roundtrip() {
  local contract key evidence checkpoint_root locked deposit_id release_event_id
  local valid_until digest report_tx mint_tx request_tx receipt withdrawal_topic withdrawal_id
  local finalize_tx recipient_hash supply balance solvent
  require_network
  contract="${WEN_CONTRACT_ADDRESS:-$(jq -r '.contractAddress // empty' "${manifest}")}"
  [[ -n "${contract}" ]] || die "set WEN_CONTRACT_ADDRESS or record contractAddress in ${manifest}"
  [[ "$(cast code --rpc-url "${rpc_url}" "${contract}")" != "0x" ]] ||
    die "contract bytecode not found"
  key="$(deployer_key)"

  evidence="$(kbb -M:dev -m credits.dev.calibration-bridge-evidence)"
  checkpoint_root="$(printf '%s\n' "${evidence}" | awk -F= '/^CHECKPOINT_ROOT=/{print $2}')"
  locked="$(printf '%s\n' "${evidence}" | awk -F= '/^LOCKED_MICRO_EN=/{print $2}')"
  deposit_id="$(printf '%s\n' "${evidence}" | awk -F= '/^DEPOSIT_ID=/{print $2}')"
  release_event_id="$(printf '%s\n' "${evidence}" | awk -F= '/^RELEASE_EVENT_ID=/{print $2}')"
  valid_until="$(( $(date +%s) + 3600 ))"

  digest="$(cast call --rpc-url "${rpc_url}" "${contract}" \
    'reserveDigest(bytes32,uint64,uint256,uint64)(bytes32)' \
    "${checkpoint_root}" 1 "${locked}" "${valid_until}")"
  sign_two RESERVE_SIGNER_1 RESERVE_SIGNER_2 "${digest}"
  report_tx="$(cast send --rpc-url "${rpc_url}" --private-key "${key}" --legacy --json \
    "${contract}" 'reportReserve(bytes32,uint64,uint256,uint64,bytes[])' \
    "${checkpoint_root}" 1 "${locked}" "${valid_until}" "${SIGNATURES}" | transaction_hash)"
  [[ -n "${report_tx}" ]] || die "reserve transaction hash missing"
  wait_success "${report_tx}"

  digest="$(cast call --rpc-url "${rpc_url}" "${contract}" \
    'depositDigest(bytes32,bytes32,address,uint256,uint64)(bytes32)' \
    "${deposit_id}" "${checkpoint_root}" "${deployer}" 10 "${valid_until}")"
  sign_two BRIDGE_SIGNER_1 BRIDGE_SIGNER_2 "${digest}"
  mint_tx="$(cast send --rpc-url "${rpc_url}" --private-key "${key}" --legacy --json \
    "${contract}" 'mintFromEngi(bytes32,bytes32,address,uint256,uint64,bytes[])' \
    "${deposit_id}" "${checkpoint_root}" "${deployer}" 10 "${valid_until}" "${SIGNATURES}" | transaction_hash)"
  [[ -n "${mint_tx}" ]] || die "mint transaction hash missing"
  wait_success "${mint_tx}"

  recipient_hash="$(cast keccak 'did:calibration-recipient')"
  request_tx="$(cast send --rpc-url "${rpc_url}" --private-key "${key}" --legacy --json \
    "${contract}" 'requestWithdrawal(uint256,bytes32)(bytes32)' 10 "${recipient_hash}" |
    transaction_hash)"
  [[ -n "${request_tx}" ]] || die "withdrawal transaction hash missing"
  wait_success "${request_tx}"
  receipt="$(cast receipt --rpc-url "${rpc_url}" --json "${request_tx}")"
  withdrawal_topic="$(cast keccak 'WithdrawalRequested(bytes32,address,bytes32,uint256,uint256)')"
  withdrawal_id="$(printf '%s' "${receipt}" | jq -r --arg topic "${withdrawal_topic}" \
    '.logs[] | select((.topics[0] | ascii_downcase) == ($topic | ascii_downcase)) | .topics[1]' |
    head -1)"
  [[ -n "${withdrawal_id}" && "${withdrawal_id}" != "null" ]] ||
    die "withdrawal id missing from receipt"

  digest="$(cast call --rpc-url "${rpc_url}" "${contract}" \
    'withdrawalDigest(bytes32,bytes32,uint64)(bytes32)' \
    "${withdrawal_id}" "${release_event_id}" "${valid_until}")"
  sign_two BRIDGE_SIGNER_1 BRIDGE_SIGNER_2 "${digest}"
  finalize_tx="$(cast send --rpc-url "${rpc_url}" --private-key "${key}" --legacy --json \
    "${contract}" 'finalizeWithdrawal(bytes32,bytes32,uint64,bytes[])' \
    "${withdrawal_id}" "${release_event_id}" "${valid_until}" "${SIGNATURES}" |
    transaction_hash)"
  unset key
  [[ -n "${finalize_tx}" ]] || die "finalize transaction hash missing"
  wait_success "${finalize_tx}"

  supply="$(cast call --rpc-url "${rpc_url}" "${contract}" 'totalSupply()(uint256)')"
  balance="$(cast call --rpc-url "${rpc_url}" "${contract}" 'balanceOf(address)(uint256)' "${deployer}")"
  solvent="$(cast call --rpc-url "${rpc_url}" "${contract}" 'solvent()(bool)')"
  [[ "${supply}" == "0" && "${balance}" == "0" && "${solvent}" == "true" ]] ||
    die "round-trip postconditions failed"

  echo "EVIDENCE_KIND=cryptographically-replayed-synthetic-corpus"
  echo "RESERVE_TX=${report_tx}"
  echo "MINT_TX=${mint_tx}"
  echo "WITHDRAWAL_TX=${request_tx}"
  echo "WITHDRAWAL_ID=${withdrawal_id}"
  echo "FINALIZE_TX=${finalize_tx}"
  echo "TOTAL_SUPPLY=${supply}"
  echo "DEPLOYER_WEN_BALANCE=${balance}"
  echo "SOLVENT=${solvent}"
}

case "${1:-}" in
  deploy) deploy ;;
  roundtrip) roundtrip ;;
  *) die "usage: $0 deploy|roundtrip" ;;
esac
