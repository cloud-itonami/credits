// SPDX-License-Identifier: Apache-2.0
pragma solidity ^0.8.24;

import { WrappedEN } from "../contracts/WrappedEN.sol";

contract TokenUser {
    function transferToken(WrappedEN token, address to, uint256 amount) external returns (bool) {
        return token.transfer(to, amount);
    }

    function approveToken(WrappedEN token, address spender, uint256 amount)
        external
        returns (bool)
    {
        return token.approve(spender, amount);
    }

    function transferFromToken(WrappedEN token, address from, address to, uint256 amount)
        external
        returns (bool)
    {
        return token.transferFrom(from, to, amount);
    }

    function withdraw(WrappedEN token, uint256 amount, bytes32 recipient)
        external
        returns (bytes32)
    {
        return token.requestWithdrawal(amount, recipient);
    }

    function tryMint(
        WrappedEN token,
        bytes32 depositId,
        bytes32 checkpointRoot,
        address recipient,
        uint256 amount
    ) external returns (bool) {
        (bool ok,) = address(token)
            .call(
                abi.encodeCall(token.mintFromEngi, (depositId, checkpointRoot, recipient, amount))
            );
        return ok;
    }

    function acceptAdmin(WrappedEN token) external {
        token.acceptAdmin();
    }
}

contract WrappedENTest {
    WrappedEN private token;
    TokenUser private alice;
    TokenUser private bob;
    TokenUser private spender;

    bytes32 private constant CHECKPOINT = keccak256("engi-checkpoint-1");
    bytes32 private constant DEPOSIT = keccak256("engi-deposit-1");
    bytes32 private constant RECIPIENT = keccak256("did:key:recipient");

    function setUp() public {
        token = new WrappedEN(address(this), address(this), address(this));
        alice = new TokenUser();
        bob = new TokenUser();
        spender = new TokenUser();
    }

    function testMetadataAndInitialState() public view {
        require(keccak256(bytes(token.name())) == keccak256("Wrapped EN"), "name");
        require(keccak256(bytes(token.symbol())) == keccak256("wEN"), "symbol");
        require(token.decimals() == 6, "decimals");
        require(token.totalSupply() == 0, "supply");
        require(token.solvent(), "initial solvency");
    }

    function testReserveBoundedMintAndDepositReplayProtection() public {
        token.reportReserve(CHECKPOINT, 1, 1_000_000);
        token.mintFromEngi(DEPOSIT, CHECKPOINT, address(alice), 600_000);

        require(token.totalSupply() == 600_000, "mint supply");
        require(token.balanceOf(address(alice)) == 600_000, "mint balance");
        require(token.processedDeposits(DEPOSIT), "deposit receipt");
        require(token.solvent(), "solvent after mint");

        (bool replayOk,) = address(token)
            .call(abi.encodeCall(token.mintFromEngi, (DEPOSIT, CHECKPOINT, address(alice), 1)));
        require(!replayOk, "deposit replay accepted");
    }

    function testCannotMintPastReportedReserveOrWrongCheckpoint() public {
        token.reportReserve(CHECKPOINT, 1, 100);
        (bool overReserve,) = address(token)
            .call(abi.encodeCall(token.mintFromEngi, (DEPOSIT, CHECKPOINT, address(alice), 101)));
        require(!overReserve, "over-reserve mint accepted");

        (bool wrongCheckpoint,) = address(token)
            .call(
                abi.encodeCall(
                    token.mintFromEngi, (DEPOSIT, keccak256("wrong"), address(alice), 100)
                )
            );
        require(!wrongCheckpoint, "wrong checkpoint accepted");
    }

    function testERC20TransferApprovalAndTransferFrom() public {
        token.reportReserve(CHECKPOINT, 1, 1_000);
        token.mintFromEngi(DEPOSIT, CHECKPOINT, address(alice), 1_000);

        require(alice.transferToken(token, address(bob), 250), "transfer false");
        require(token.balanceOf(address(alice)) == 750, "alice transfer balance");
        require(token.balanceOf(address(bob)) == 250, "bob transfer balance");

        require(alice.approveToken(token, address(spender), 300), "approve false");
        require(
            spender.transferFromToken(token, address(alice), address(bob), 175),
            "transferFrom false"
        );
        require(token.allowance(address(alice), address(spender)) == 125, "allowance");
        require(token.balanceOf(address(alice)) == 575, "alice transferFrom balance");
        require(token.balanceOf(address(bob)) == 425, "bob transferFrom balance");
    }

    function testWithdrawalBurnAndFinalizationReplayProtection() public {
        token.reportReserve(CHECKPOINT, 1, 500);
        token.mintFromEngi(DEPOSIT, CHECKPOINT, address(alice), 500);
        bytes32 withdrawalId = alice.withdraw(token, 200, RECIPIENT);

        require(token.totalSupply() == 300, "burn supply");
        require(token.balanceOf(address(alice)) == 300, "burn balance");
        (address sender, bytes32 recipient, uint256 amount, bool finalized) =
            token.withdrawals(withdrawalId);
        require(sender == address(alice), "withdraw sender");
        require(recipient == RECIPIENT, "withdraw recipient");
        require(amount == 200 && !finalized, "withdraw state");

        bytes32 engiEvent = keccak256("engi-withdrawal-event");
        token.finalizeWithdrawal(withdrawalId, engiEvent);
        (,,, finalized) = token.withdrawals(withdrawalId);
        require(finalized, "not finalized");
        (bool replayOk,) = address(token)
            .call(
                abi.encodeCall(
                    token.finalizeWithdrawal, (withdrawalId, keccak256("second-engi-event"))
                )
            );
        require(!replayOk, "withdrawal replay accepted");

        bytes32 secondWithdrawal = alice.withdraw(token, 50, keccak256("second-recipient"));
        (bool eventReuseOk,) = address(token)
            .call(abi.encodeCall(token.finalizeWithdrawal, (secondWithdrawal, engiEvent)));
        require(!eventReuseOk, "ENGI withdrawal event reused");
    }

    function testCheckpointSequenceCannotRollBack() public {
        token.reportReserve(CHECKPOINT, 2, 1_000);
        (bool rollbackOk,) =
            address(token).call(abi.encodeCall(token.reportReserve, (keccak256("old"), 1, 2_000)));
        require(!rollbackOk, "checkpoint rollback accepted");
        require(token.latestCheckpointSequence() == 2, "checkpoint sequence changed");
        require(token.reportedLockedMicroEn() == 1_000, "stale reserve accepted");
    }

    function testReserveDeficitPausesTransfersButAllowsBurnForWithdrawal() public {
        token.reportReserve(CHECKPOINT, 1, 1_000);
        token.mintFromEngi(DEPOSIT, CHECKPOINT, address(alice), 1_000);
        bytes32 deficitCheckpoint = keccak256("deficit");
        token.reportReserve(deficitCheckpoint, 2, 500);
        require(token.paused(), "not paused on deficit");
        require(!token.solvent(), "deficit reported solvent");

        (bool transferOk,) =
            address(alice).call(abi.encodeCall(alice.transferToken, (token, address(bob), 1)));
        require(!transferOk, "transfer while paused");

        alice.withdraw(token, 500, RECIPIENT);
        require(token.totalSupply() == 500, "withdraw did not restore reserve bound");
        require(token.solvent(), "not solvent after burn");
        token.unpause();
        require(!token.paused(), "not unpaused");
    }

    function testOnlyBridgeCanMint() public {
        token.reportReserve(CHECKPOINT, 1, 100);
        require(
            !alice.tryMint(token, DEPOSIT, CHECKPOINT, address(alice), 100), "unauthorized mint"
        );
    }

    function testTwoStepAdminTransfer() public {
        token.startAdminTransfer(address(alice));
        alice.acceptAdmin(token);
        require(token.admin() == address(alice), "admin transfer");
        (bool oldAdminOk,) = address(token).call(abi.encodeCall(token.pause, ()));
        require(!oldAdminOk, "old admin retained power");
    }

    function testFuzzReserveConservation(uint96 reserveSeed, uint96 mintSeed) public {
        uint256 reserve = uint256(reserveSeed) + 1;
        uint256 amount = (uint256(mintSeed) % reserve) + 1;
        token.reportReserve(CHECKPOINT, 1, reserve);
        token.mintFromEngi(DEPOSIT, CHECKPOINT, address(alice), amount);
        require(token.totalSupply() == amount, "fuzz supply");
        require(token.totalSupply() <= token.reportedLockedMicroEn(), "reserve invariant");
        require(token.solvent(), "fuzz solvency");
    }
}
