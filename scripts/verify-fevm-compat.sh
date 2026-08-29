#!/usr/bin/env bash
set -euo pipefail

forge build
forge test -vv

bytecode="$(forge inspect WrappedEN deployedBytecode)"
if [[ -z "${bytecode}" || "${bytecode}" == "0x" ]]; then
  echo "REFUSED: WrappedEN deployed bytecode is empty" >&2
  exit 1
fi

disassembly="$(cast disassemble "${bytecode}")"
if grep -Eq '(^|[[:space:]])(CALLCODE|SELFDESTRUCT)([[:space:]]|$)' <<<"${disassembly}"; then
  echo "REFUSED: FEVM-sensitive opcode found" >&2
  exit 1
fi

for signature in \
  'totalSupply()' \
  'balanceOf(address)' \
  'transfer(address,uint256)' \
  'allowance(address,address)' \
  'approve(address,uint256)' \
  'transferFrom(address,address,uint256)'
do
  cast sig "${signature}" >/dev/null
done

echo "PASS: ERC-20 ABI, Solidity build/tests, and FEVM-sensitive opcode gate"

