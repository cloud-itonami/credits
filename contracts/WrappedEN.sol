// SPDX-License-Identifier: Apache-2.0
pragma solidity ^0.8.24;

import { IERC20 } from "./IERC20.sol";

/// @title Wrapped EN (wEN)
/// @notice FEVM/EVM representation of positive EN locked in the canonical ENGI ledger.
/// @dev ENGI remains authoritative. Mint/reserve/settlement evidence requires independent
///      threshold committees; submitting a proof is permissionless.
contract WrappedEN is IERC20 {
    string public constant name = "Wrapped EN";
    string public constant symbol = "wEN";
    uint8 public constant decimals = 6;

    bytes32 public constant RESERVE_TYPEHASH = keccak256(
        "ReserveAttestation(bytes32 checkpointRoot,uint64 checkpointSequence,uint256 lockedMicroEn,uint64 validUntil)"
    );
    bytes32 public constant DEPOSIT_TYPEHASH = keccak256(
        "DepositAttestation(bytes32 depositId,bytes32 checkpointRoot,address recipient,uint256 amount,uint64 validUntil)"
    );
    bytes32 public constant WITHDRAWAL_TYPEHASH = keccak256(
        "WithdrawalAttestation(bytes32 withdrawalId,bytes32 engiEventId,uint64 validUntil)"
    );
    bytes32 private constant DOMAIN_TYPEHASH = keccak256(
        "EIP712Domain(string name,string version,uint256 chainId,address verifyingContract)"
    );
    bytes32 private constant DOMAIN_NAME_HASH = keccak256("Wrapped EN Bridge");
    bytes32 private constant DOMAIN_VERSION_HASH = keccak256("2");
    uint256 private constant SECP256K1_N_DIV_2 =
        0x7fffffffffffffffffffffffffffffff5d576e7357a4501ddfe92f46681b20a0;

    error Unauthorized();
    error ZeroAddress();
    error ZeroAmount();
    error Paused();
    error NotPaused();
    error InsufficientBalance();
    error InsufficientAllowance();
    error DepositAlreadyProcessed();
    error EngiEventAlreadyProcessed();
    error UnknownWithdrawal();
    error WithdrawalAlreadyFinalized();
    error InvalidEvidence();
    error EvidenceExpired();
    error ReserveExceeded();
    error RoleTransferNotPending();
    error StaleCheckpoint();
    error InvalidCommittee();
    error InvalidThreshold();
    error InvalidSignature();
    error DuplicateOrUnsortedSigner();
    error InsufficientCommitteeSignatures();

    event EngiDepositMinted(
        bytes32 indexed depositId,
        bytes32 indexed checkpointRoot,
        address indexed recipient,
        uint256 amount
    );
    event WithdrawalRequested(
        bytes32 indexed withdrawalId,
        address indexed sender,
        bytes32 indexed engiRecipientHash,
        uint256 amount,
        uint256 nonce
    );
    event WithdrawalFinalized(bytes32 indexed withdrawalId, bytes32 indexed engiEventId);
    event ReserveReported(
        bytes32 indexed checkpointRoot,
        uint64 indexed checkpointSequence,
        uint256 lockedMicroEn,
        bool solvent
    );
    event PausedStateChanged(bool paused);
    event AdminTransferStarted(address indexed currentAdmin, address indexed pendingAdmin);
    event AdminTransferred(address indexed previousAdmin, address indexed newAdmin);

    struct Withdrawal {
        address sender;
        bytes32 engiRecipientHash;
        uint256 amount;
        bool finalized;
    }

    uint256 public override totalSupply;
    uint256 public reportedLockedMicroEn;
    uint256 public withdrawalNonce;
    bytes32 public latestCheckpointRoot;
    uint64 public latestCheckpointSequence;
    bool public paused;

    address public admin;
    address public pendingAdmin;
    uint8 public immutable bridgeThreshold;
    uint8 public immutable reserveThreshold;
    mapping(address => bool) public bridgeSigner;
    mapping(address => bool) public reserveSigner;

    mapping(address => uint256) private _balances;
    mapping(address => mapping(address => uint256)) private _allowances;
    mapping(bytes32 => bool) public processedDeposits;
    mapping(bytes32 => bool) public processedWithdrawalEvents;
    mapping(bytes32 => Withdrawal) public withdrawals;

    modifier onlyAdmin() {
        if (msg.sender != admin) revert Unauthorized();
        _;
    }

    constructor(
        address admin_,
        address[] memory bridgeSigners_,
        uint8 bridgeThreshold_,
        address[] memory reserveSigners_,
        uint8 reserveThreshold_
    ) {
        if (admin_ == address(0)) revert ZeroAddress();
        _validateThreshold(bridgeSigners_.length, bridgeThreshold_);
        _validateThreshold(reserveSigners_.length, reserveThreshold_);
        admin = admin_;
        bridgeThreshold = bridgeThreshold_;
        reserveThreshold = reserveThreshold_;

        for (uint256 i; i < bridgeSigners_.length; ++i) {
            address signer = bridgeSigners_[i];
            if (signer == address(0) || bridgeSigner[signer]) revert InvalidCommittee();
            bridgeSigner[signer] = true;
        }
        for (uint256 i; i < reserveSigners_.length; ++i) {
            address signer = reserveSigners_[i];
            if (signer == address(0) || reserveSigner[signer] || bridgeSigner[signer]) {
                revert InvalidCommittee();
            }
            reserveSigner[signer] = true;
        }
    }

    function balanceOf(address account) external view override returns (uint256) {
        return _balances[account];
    }

    function allowance(address owner, address spender) external view override returns (uint256) {
        return _allowances[owner][spender];
    }

    function transfer(address to, uint256 value) external override returns (bool) {
        _transfer(msg.sender, to, value);
        return true;
    }

    function approve(address spender, uint256 value) external override returns (bool) {
        if (spender == address(0)) revert ZeroAddress();
        _allowances[msg.sender][spender] = value;
        emit Approval(msg.sender, spender, value);
        return true;
    }

    function transferFrom(address from, address to, uint256 value)
        external
        override
        returns (bool)
    {
        uint256 allowed = _allowances[from][msg.sender];
        if (allowed != type(uint256).max) {
            if (allowed < value) revert InsufficientAllowance();
            unchecked {
                _allowances[from][msg.sender] = allowed - value;
            }
            emit Approval(from, msg.sender, _allowances[from][msg.sender]);
        }
        _transfer(from, to, value);
        return true;
    }

    function reportReserve(
        bytes32 checkpointRoot,
        uint64 checkpointSequence,
        uint256 lockedMicroEn,
        uint64 validUntil,
        bytes[] calldata signatures
    ) external {
        if (checkpointRoot == bytes32(0)) revert InvalidEvidence();
        if (checkpointSequence <= latestCheckpointSequence) revert StaleCheckpoint();
        bytes32 digest =
            reserveDigest(checkpointRoot, checkpointSequence, lockedMicroEn, validUntil);
        _verifyCommittee(digest, signatures, false, validUntil);

        latestCheckpointRoot = checkpointRoot;
        latestCheckpointSequence = checkpointSequence;
        reportedLockedMicroEn = lockedMicroEn;
        bool reserveSolvent = lockedMicroEn >= totalSupply;
        if (!reserveSolvent && !paused) {
            paused = true;
            emit PausedStateChanged(true);
        }
        emit ReserveReported(checkpointRoot, checkpointSequence, lockedMicroEn, reserveSolvent);
    }

    function mintFromEngi(
        bytes32 depositId,
        bytes32 checkpointRoot,
        address recipient,
        uint256 amount,
        uint64 validUntil,
        bytes[] calldata signatures
    ) external {
        if (paused) revert Paused();
        if (recipient == address(0)) revert ZeroAddress();
        if (amount == 0) revert ZeroAmount();
        if (depositId == bytes32(0) || checkpointRoot == bytes32(0)) revert InvalidEvidence();
        if (processedDeposits[depositId]) revert DepositAlreadyProcessed();
        if (checkpointRoot != latestCheckpointRoot) revert InvalidEvidence();
        if (totalSupply + amount > reportedLockedMicroEn) revert ReserveExceeded();
        _verifyCommittee(
            depositDigest(depositId, checkpointRoot, recipient, amount, validUntil),
            signatures,
            true,
            validUntil
        );

        processedDeposits[depositId] = true;
        totalSupply += amount;
        _balances[recipient] += amount;
        emit Transfer(address(0), recipient, amount);
        emit EngiDepositMinted(depositId, checkpointRoot, recipient, amount);
    }

    function requestWithdrawal(uint256 amount, bytes32 engiRecipientHash)
        external
        returns (bytes32 withdrawalId)
    {
        if (amount == 0) revert ZeroAmount();
        if (engiRecipientHash == bytes32(0)) revert InvalidEvidence();
        _burn(msg.sender, amount);

        uint256 nonce = ++withdrawalNonce;
        withdrawalId = keccak256(
            abi.encode(block.chainid, address(this), msg.sender, nonce, engiRecipientHash, amount)
        );
        withdrawals[withdrawalId] = Withdrawal({
            sender: msg.sender,
            engiRecipientHash: engiRecipientHash,
            amount: amount,
            finalized: false
        });
        emit WithdrawalRequested(withdrawalId, msg.sender, engiRecipientHash, amount, nonce);
    }

    function finalizeWithdrawal(
        bytes32 withdrawalId,
        bytes32 engiEventId,
        uint64 validUntil,
        bytes[] calldata signatures
    ) external {
        Withdrawal storage withdrawal = withdrawals[withdrawalId];
        if (withdrawal.sender == address(0)) revert UnknownWithdrawal();
        if (withdrawal.finalized) revert WithdrawalAlreadyFinalized();
        if (engiEventId == bytes32(0)) revert InvalidEvidence();
        if (processedWithdrawalEvents[engiEventId]) revert EngiEventAlreadyProcessed();
        _verifyCommittee(
            withdrawalDigest(withdrawalId, engiEventId, validUntil), signatures, true, validUntil
        );
        processedWithdrawalEvents[engiEventId] = true;
        withdrawal.finalized = true;
        emit WithdrawalFinalized(withdrawalId, engiEventId);
    }

    function reserveDigest(
        bytes32 checkpointRoot,
        uint64 checkpointSequence,
        uint256 lockedMicroEn,
        uint64 validUntil
    ) public view returns (bytes32) {
        return _typedDataHash(
            keccak256(
                abi.encode(
                    RESERVE_TYPEHASH, checkpointRoot, checkpointSequence, lockedMicroEn, validUntil
                )
            )
        );
    }

    function depositDigest(
        bytes32 depositId,
        bytes32 checkpointRoot,
        address recipient,
        uint256 amount,
        uint64 validUntil
    ) public view returns (bytes32) {
        return _typedDataHash(
            keccak256(
                abi.encode(
                    DEPOSIT_TYPEHASH, depositId, checkpointRoot, recipient, amount, validUntil
                )
            )
        );
    }

    function withdrawalDigest(bytes32 withdrawalId, bytes32 engiEventId, uint64 validUntil)
        public
        view
        returns (bytes32)
    {
        return _typedDataHash(
            keccak256(abi.encode(WITHDRAWAL_TYPEHASH, withdrawalId, engiEventId, validUntil))
        );
    }

    function domainSeparator() public view returns (bytes32) {
        return keccak256(
            abi.encode(
                DOMAIN_TYPEHASH, DOMAIN_NAME_HASH, DOMAIN_VERSION_HASH, block.chainid, address(this)
            )
        );
    }

    function pause() external onlyAdmin {
        if (paused) revert Paused();
        paused = true;
        emit PausedStateChanged(true);
    }

    function unpause() external onlyAdmin {
        if (!paused) revert NotPaused();
        if (reportedLockedMicroEn < totalSupply) revert ReserveExceeded();
        paused = false;
        emit PausedStateChanged(false);
    }

    function startAdminTransfer(address next) external onlyAdmin {
        if (next == address(0)) revert ZeroAddress();
        pendingAdmin = next;
        emit AdminTransferStarted(admin, next);
    }

    function acceptAdmin() external {
        if (msg.sender != pendingAdmin) revert RoleTransferNotPending();
        address previous = admin;
        admin = msg.sender;
        pendingAdmin = address(0);
        emit AdminTransferred(previous, msg.sender);
    }

    function solvent() external view returns (bool) {
        return totalSupply <= reportedLockedMicroEn;
    }

    function _verifyCommittee(
        bytes32 digest,
        bytes[] calldata signatures,
        bool useBridgeCommittee,
        uint64 validUntil
    ) private view {
        if (validUntil < block.timestamp) revert EvidenceExpired();
        uint256 threshold = useBridgeCommittee ? bridgeThreshold : reserveThreshold;
        if (signatures.length < threshold) revert InsufficientCommitteeSignatures();

        address previous;
        uint256 valid;
        for (uint256 i; i < signatures.length; ++i) {
            address signer = _recover(digest, signatures[i]);
            if (signer <= previous) revert DuplicateOrUnsortedSigner();
            previous = signer;
            bool admitted = useBridgeCommittee ? bridgeSigner[signer] : reserveSigner[signer];
            if (!admitted) revert InvalidSignature();
            ++valid;
        }
        if (valid < threshold) revert InsufficientCommitteeSignatures();
    }

    function _recover(bytes32 digest, bytes calldata signature)
        private
        pure
        returns (address signer)
    {
        if (signature.length != 65) revert InvalidSignature();
        bytes32 r;
        bytes32 s;
        uint8 v;
        assembly ("memory-safe") {
            r := calldataload(signature.offset)
            s := calldataload(add(signature.offset, 32))
            v := byte(0, calldataload(add(signature.offset, 64)))
        }
        if (uint256(s) > SECP256K1_N_DIV_2 || (v != 27 && v != 28)) revert InvalidSignature();
        signer = ecrecover(digest, v, r, s);
        if (signer == address(0)) revert InvalidSignature();
    }

    function _typedDataHash(bytes32 structHash) private view returns (bytes32) {
        return keccak256(abi.encodePacked("\x19\x01", domainSeparator(), structHash));
    }

    function _validateThreshold(uint256 size, uint8 threshold) private pure {
        if (size == 0 || size > type(uint8).max) revert InvalidCommittee();
        if (threshold == 0 || threshold > size) revert InvalidThreshold();
    }

    function _transfer(address from, address to, uint256 value) private {
        if (paused) revert Paused();
        if (to == address(0)) revert ZeroAddress();
        uint256 fromBalance = _balances[from];
        if (fromBalance < value) revert InsufficientBalance();
        unchecked {
            _balances[from] = fromBalance - value;
            _balances[to] += value;
        }
        emit Transfer(from, to, value);
    }

    function _burn(address from, uint256 amount) private {
        uint256 fromBalance = _balances[from];
        if (fromBalance < amount) revert InsufficientBalance();
        unchecked {
            _balances[from] = fromBalance - amount;
            totalSupply -= amount;
        }
        emit Transfer(from, address(0), amount);
    }
}
