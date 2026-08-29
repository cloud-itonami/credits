// SPDX-License-Identifier: Apache-2.0
pragma solidity ^0.8.24;

import { IERC20 } from "./IERC20.sol";

/// @title Wrapped EN (wEN)
/// @notice FEVM/EVM ERC-20 representation of positive EN locked in the canonical ENGI ledger.
/// @dev This contract is a bridge representation, not the ENGI ledger and not an EN minter.
contract WrappedEN is IERC20 {
    string public constant name = "Wrapped EN";
    string public constant symbol = "wEN";
    uint8 public constant decimals = 6; // ENGI amounts are integer micro-EN.

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
    error ReserveExceeded();
    error RoleTransferNotPending();
    error StaleCheckpoint();

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
    event BridgeTransferStarted(address indexed currentBridge, address indexed pendingBridge);
    event BridgeTransferred(address indexed previousBridge, address indexed newBridge);
    event ReserveOracleTransferStarted(
        address indexed currentOracle, address indexed pendingOracle
    );
    event ReserveOracleTransferred(address indexed previousOracle, address indexed newOracle);

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
    address public bridge;
    address public pendingBridge;
    address public reserveOracle;
    address public pendingReserveOracle;

    mapping(address => uint256) private _balances;
    mapping(address => mapping(address => uint256)) private _allowances;
    mapping(bytes32 => bool) public processedDeposits;
    mapping(bytes32 => bool) public processedWithdrawalEvents;
    mapping(bytes32 => Withdrawal) public withdrawals;

    modifier onlyAdmin() {
        if (msg.sender != admin) revert Unauthorized();
        _;
    }

    modifier onlyBridge() {
        if (msg.sender != bridge) revert Unauthorized();
        _;
    }

    modifier onlyReserveOracle() {
        if (msg.sender != reserveOracle) revert Unauthorized();
        _;
    }

    constructor(address admin_, address bridge_, address reserveOracle_) {
        if (admin_ == address(0) || bridge_ == address(0) || reserveOracle_ == address(0)) {
            revert ZeroAddress();
        }
        admin = admin_;
        bridge = bridge_;
        reserveOracle = reserveOracle_;
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

    /// @notice Report EN locked at the bridge DID from an independently replayed checkpoint.
    /// @dev A deficit pauses transfers but withdrawals remain possible, reducing supply.
    function reportReserve(bytes32 checkpointRoot, uint64 checkpointSequence, uint256 lockedMicroEn)
        external
        onlyReserveOracle
    {
        if (checkpointRoot == bytes32(0)) revert InvalidEvidence();
        if (checkpointSequence <= latestCheckpointSequence) revert StaleCheckpoint();
        latestCheckpointRoot = checkpointRoot;
        latestCheckpointSequence = checkpointSequence;
        reportedLockedMicroEn = lockedMicroEn;
        bool reserveSolvent = lockedMicroEn >= totalSupply;
        if (!reserveSolvent) {
            paused = true;
            emit PausedStateChanged(true);
        }
        emit ReserveReported(checkpointRoot, checkpointSequence, lockedMicroEn, reserveSolvent);
    }

    /// @notice Mint wEN once for a canonical ENGI deposit into the bridge DID.
    function mintFromEngi(
        bytes32 depositId,
        bytes32 checkpointRoot,
        address recipient,
        uint256 amount
    ) external onlyBridge {
        if (paused) revert Paused();
        if (recipient == address(0)) revert ZeroAddress();
        if (amount == 0) revert ZeroAmount();
        if (depositId == bytes32(0) || checkpointRoot == bytes32(0)) revert InvalidEvidence();
        if (processedDeposits[depositId]) revert DepositAlreadyProcessed();
        if (checkpointRoot != latestCheckpointRoot) revert InvalidEvidence();
        if (totalSupply + amount > reportedLockedMicroEn) revert ReserveExceeded();

        processedDeposits[depositId] = true;
        totalSupply += amount;
        _balances[recipient] += amount;
        emit Transfer(address(0), recipient, amount);
        emit EngiDepositMinted(depositId, checkpointRoot, recipient, amount);
    }

    /// @notice Burn wEN and create a request for an ENGI transfer from the bridge DID.
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

    /// @notice Attach the canonical ENGI transfer event that settled a burn request.
    function finalizeWithdrawal(bytes32 withdrawalId, bytes32 engiEventId) external onlyBridge {
        Withdrawal storage withdrawal = withdrawals[withdrawalId];
        if (withdrawal.sender == address(0)) revert UnknownWithdrawal();
        if (withdrawal.finalized) revert WithdrawalAlreadyFinalized();
        if (engiEventId == bytes32(0)) revert InvalidEvidence();
        if (processedWithdrawalEvents[engiEventId]) revert EngiEventAlreadyProcessed();
        processedWithdrawalEvents[engiEventId] = true;
        withdrawal.finalized = true;
        emit WithdrawalFinalized(withdrawalId, engiEventId);
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

    function startBridgeTransfer(address next) external onlyAdmin {
        if (next == address(0)) revert ZeroAddress();
        pendingBridge = next;
        emit BridgeTransferStarted(bridge, next);
    }

    function acceptBridge() external {
        if (msg.sender != pendingBridge) revert RoleTransferNotPending();
        address previous = bridge;
        bridge = msg.sender;
        pendingBridge = address(0);
        emit BridgeTransferred(previous, msg.sender);
    }

    function startReserveOracleTransfer(address next) external onlyAdmin {
        if (next == address(0)) revert ZeroAddress();
        pendingReserveOracle = next;
        emit ReserveOracleTransferStarted(reserveOracle, next);
    }

    function acceptReserveOracle() external {
        if (msg.sender != pendingReserveOracle) revert RoleTransferNotPending();
        address previous = reserveOracle;
        reserveOracle = msg.sender;
        pendingReserveOracle = address(0);
        emit ReserveOracleTransferred(previous, msg.sender);
    }

    function solvent() external view returns (bool) {
        return totalSupply <= reportedLockedMicroEn;
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
