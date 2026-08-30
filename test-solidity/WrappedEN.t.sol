// SPDX-License-Identifier: Apache-2.0
pragma solidity ^0.8.24;

import { WrappedEN } from "../contracts/WrappedEN.sol";

interface Vm {
    function addr(uint256 privateKey) external returns (address);
    function sign(uint256 privateKey, bytes32 digest)
        external
        returns (uint8 v, bytes32 r, bytes32 s);
    function warp(uint256 timestamp) external;
}

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

    function acceptAdmin(WrappedEN token) external {
        token.acceptAdmin();
    }
}

contract WrappedENTest {
    Vm private constant vm = Vm(address(uint160(uint256(keccak256("hevm cheat code")))));

    uint256 private constant BRIDGE_PK_1 = 0xA11CE;
    uint256 private constant BRIDGE_PK_2 = 0xB0B;
    uint256 private constant BRIDGE_PK_3 = 0xCAFE;
    uint256 private constant RESERVE_PK_1 = 0xD00D;
    uint256 private constant RESERVE_PK_2 = 0xE11E;
    uint256 private constant RESERVE_PK_3 = 0xF00D;

    bytes32 private constant CHECKPOINT = keccak256("engi-checkpoint-1");
    bytes32 private constant DEPOSIT = keccak256("engi-deposit-1");
    bytes32 private constant RECIPIENT = keccak256("did:key:recipient");

    WrappedEN private token;
    TokenUser private alice;
    TokenUser private bob;
    TokenUser private spender;

    function setUp() public {
        address[] memory bridgeSigners = new address[](3);
        bridgeSigners[0] = vm.addr(BRIDGE_PK_1);
        bridgeSigners[1] = vm.addr(BRIDGE_PK_2);
        bridgeSigners[2] = vm.addr(BRIDGE_PK_3);
        address[] memory reserveSigners = new address[](3);
        reserveSigners[0] = vm.addr(RESERVE_PK_1);
        reserveSigners[1] = vm.addr(RESERVE_PK_2);
        reserveSigners[2] = vm.addr(RESERVE_PK_3);
        token = new WrappedEN(address(this), bridgeSigners, 2, reserveSigners, 2);
        alice = new TokenUser();
        bob = new TokenUser();
        spender = new TokenUser();
    }

    function testMetadataAndIndependentCommittees() public {
        require(keccak256(bytes(token.name())) == keccak256("Wrapped EN"), "name");
        require(keccak256(bytes(token.symbol())) == keccak256("wEN"), "symbol");
        require(token.decimals() == 6, "decimals");
        require(token.bridgeThreshold() == 2 && token.reserveThreshold() == 2, "thresholds");
        require(token.bridgeSigner(vm.addr(BRIDGE_PK_1)), "bridge signer");
        require(token.reserveSigner(vm.addr(RESERVE_PK_1)), "reserve signer");
        require(!token.reserveSigner(vm.addr(BRIDGE_PK_1)), "committee overlap");
        require(token.totalSupply() == 0 && token.solvent(), "initial state");
    }

    function testThresholdReserveAndMint() public {
        _reportReserve(CHECKPOINT, 1, 1_000_000);
        _mint(DEPOSIT, CHECKPOINT, address(alice), 600_000);
        require(token.totalSupply() == 600_000, "supply");
        require(token.balanceOf(address(alice)) == 600_000, "balance");
        require(token.processedDeposits(DEPOSIT), "deposit receipt");
        require(token.solvent(), "solvent");
    }

    function testInsufficientAndWrongCommitteeSignaturesFail() public {
        uint64 deadline = _deadline();
        bytes32 digest = token.reserveDigest(CHECKPOINT, 1, 100, deadline);
        bytes[] memory oneSignature = new bytes[](1);
        oneSignature[0] = _signature(RESERVE_PK_1, digest);
        (bool oneOk,) = address(token)
            .call(abi.encodeCall(token.reportReserve, (CHECKPOINT, 1, 100, deadline, oneSignature)));
        require(!oneOk, "one signature accepted");

        bytes[] memory wrongCommittee = _signatures(BRIDGE_PK_1, BRIDGE_PK_2, digest);
        (bool wrongOk,) = address(token)
            .call(
                abi.encodeCall(token.reportReserve, (CHECKPOINT, 1, 100, deadline, wrongCommittee))
            );
        require(!wrongOk, "wrong committee accepted");
    }

    function testDuplicateOrUnsortedSignersFail() public {
        uint64 deadline = _deadline();
        bytes32 digest = token.reserveDigest(CHECKPOINT, 1, 100, deadline);
        bytes memory first = _signature(RESERVE_PK_1, digest);
        bytes[] memory duplicate = new bytes[](2);
        duplicate[0] = first;
        duplicate[1] = first;
        (bool duplicateOk,) = address(token)
            .call(abi.encodeCall(token.reportReserve, (CHECKPOINT, 1, 100, deadline, duplicate)));
        require(!duplicateOk, "duplicate signer accepted");

        bytes[] memory sorted = _signatures(RESERVE_PK_1, RESERVE_PK_2, digest);
        bytes[] memory unsorted = new bytes[](2);
        unsorted[0] = sorted[1];
        unsorted[1] = sorted[0];
        (bool unsortedOk,) = address(token)
            .call(abi.encodeCall(token.reportReserve, (CHECKPOINT, 1, 100, deadline, unsorted)));
        require(!unsortedOk, "unsorted signer accepted");
    }

    function testExpiredEvidenceFails() public {
        uint64 deadline = uint64(block.timestamp + 10);
        bytes32 digest = token.reserveDigest(CHECKPOINT, 1, 100, deadline);
        bytes[] memory signatures = _signatures(RESERVE_PK_1, RESERVE_PK_2, digest);
        vm.warp(block.timestamp + 11);
        (bool ok,) = address(token)
            .call(abi.encodeCall(token.reportReserve, (CHECKPOINT, 1, 100, deadline, signatures)));
        require(!ok, "expired evidence accepted");
    }

    function testReserveAndDepositReplayProtection() public {
        _reportReserve(CHECKPOINT, 1, 1_000);
        _mint(DEPOSIT, CHECKPOINT, address(alice), 600);

        uint64 deadline = _deadline();
        bytes32 digest = token.depositDigest(DEPOSIT, CHECKPOINT, address(alice), 1, deadline);
        bytes[] memory signatures = _signatures(BRIDGE_PK_1, BRIDGE_PK_2, digest);
        (bool replayOk,) = address(token)
            .call(
                abi.encodeCall(
                    token.mintFromEngi,
                    (DEPOSIT, CHECKPOINT, address(alice), 1, deadline, signatures)
                )
            );
        require(!replayOk, "deposit replay accepted");

        uint64 reserveDeadline = _deadline();
        bytes32 oldRoot = keccak256("old");
        bytes32 reserveDigest = token.reserveDigest(oldRoot, 1, 2_000, reserveDeadline);
        bytes[] memory reserveSignatures = _signatures(RESERVE_PK_1, RESERVE_PK_2, reserveDigest);
        (bool rollbackOk,) = address(token)
            .call(
                abi.encodeCall(
                    token.reportReserve, (oldRoot, 1, 2_000, reserveDeadline, reserveSignatures)
                )
            );
        require(!rollbackOk, "checkpoint rollback accepted");
    }

    function testCannotMintPastReserveOrWrongCheckpoint() public {
        _reportReserve(CHECKPOINT, 1, 100);
        uint64 deadline = _deadline();
        bytes32 overDigest = token.depositDigest(DEPOSIT, CHECKPOINT, address(alice), 101, deadline);
        bytes[] memory overSignatures = _signatures(BRIDGE_PK_1, BRIDGE_PK_2, overDigest);
        (bool overOk,) = address(token)
            .call(
                abi.encodeCall(
                    token.mintFromEngi,
                    (DEPOSIT, CHECKPOINT, address(alice), 101, deadline, overSignatures)
                )
            );
        require(!overOk, "over-reserve mint accepted");

        bytes32 wrongRoot = keccak256("wrong");
        bytes32 wrongDigest = token.depositDigest(DEPOSIT, wrongRoot, address(alice), 100, deadline);
        bytes[] memory wrongSignatures = _signatures(BRIDGE_PK_1, BRIDGE_PK_2, wrongDigest);
        (bool wrongOk,) = address(token)
            .call(
                abi.encodeCall(
                    token.mintFromEngi,
                    (DEPOSIT, wrongRoot, address(alice), 100, deadline, wrongSignatures)
                )
            );
        require(!wrongOk, "wrong checkpoint accepted");
    }

    function testERC20TransferApprovalAndTransferFrom() public {
        _reportReserve(CHECKPOINT, 1, 1_000);
        _mint(DEPOSIT, CHECKPOINT, address(alice), 1_000);
        require(alice.transferToken(token, address(bob), 250), "transfer");
        require(alice.approveToken(token, address(spender), 300), "approve");
        require(spender.transferFromToken(token, address(alice), address(bob), 175), "transferFrom");
        require(token.allowance(address(alice), address(spender)) == 125, "allowance");
        require(token.balanceOf(address(alice)) == 575, "alice balance");
        require(token.balanceOf(address(bob)) == 425, "bob balance");
    }

    function testWithdrawalThresholdFinalizationAndEventReplayProtection() public {
        _reportReserve(CHECKPOINT, 1, 500);
        _mint(DEPOSIT, CHECKPOINT, address(alice), 500);
        bytes32 withdrawalId = alice.withdraw(token, 200, RECIPIENT);
        require(token.totalSupply() == 300, "burn supply");

        bytes32 engiEvent = keccak256("engi-withdrawal-event");
        _finalize(withdrawalId, engiEvent);
        (,,, bool finalized) = token.withdrawals(withdrawalId);
        require(finalized, "not finalized");

        bytes32 secondWithdrawal = alice.withdraw(token, 50, keccak256("second-recipient"));
        uint64 deadline = _deadline();
        bytes32 digest = token.withdrawalDigest(secondWithdrawal, engiEvent, deadline);
        bytes[] memory signatures = _signatures(BRIDGE_PK_1, BRIDGE_PK_2, digest);
        (bool reuseOk,) = address(token)
            .call(
                abi.encodeCall(
                    token.finalizeWithdrawal, (secondWithdrawal, engiEvent, deadline, signatures)
                )
            );
        require(!reuseOk, "ENGI event reused");
    }

    function testReserveDeficitPausesTransfersButAllowsBurn() public {
        _reportReserve(CHECKPOINT, 1, 1_000);
        _mint(DEPOSIT, CHECKPOINT, address(alice), 1_000);
        _reportReserve(keccak256("deficit"), 2, 500);
        require(token.paused() && !token.solvent(), "deficit state");
        (bool transferOk,) =
            address(alice).call(abi.encodeCall(alice.transferToken, (token, address(bob), 1)));
        require(!transferOk, "paused transfer");
        alice.withdraw(token, 500, RECIPIENT);
        require(token.solvent(), "burn did not restore solvency");
        token.unpause();
        require(!token.paused(), "not unpaused");
    }

    function testTwoStepAdminTransferAndAdminCannotMintWithoutCommittee() public {
        token.startAdminTransfer(address(alice));
        alice.acceptAdmin(token);
        require(token.admin() == address(alice), "admin transfer");
        (bool oldAdminOk,) = address(token).call(abi.encodeCall(token.pause, ()));
        require(!oldAdminOk, "old admin retained power");

        bytes[] memory none = new bytes[](0);
        (bool mintOk,) = address(token)
            .call(
                abi.encodeCall(
                    token.mintFromEngi, (DEPOSIT, CHECKPOINT, address(this), 1, _deadline(), none)
                )
            );
        require(!mintOk, "admin minted without committee");
    }

    function testFuzzReserveConservation(uint96 reserveSeed, uint96 mintSeed) public {
        uint256 reserve = uint256(reserveSeed) + 1;
        uint256 amount = (uint256(mintSeed) % reserve) + 1;
        _reportReserve(CHECKPOINT, 1, reserve);
        _mint(DEPOSIT, CHECKPOINT, address(alice), amount);
        require(token.totalSupply() == amount, "fuzz supply");
        require(token.totalSupply() <= token.reportedLockedMicroEn(), "reserve invariant");
    }

    function _reportReserve(bytes32 root, uint64 sequence, uint256 locked) private {
        uint64 deadline = _deadline();
        bytes32 digest = token.reserveDigest(root, sequence, locked, deadline);
        token.reportReserve(
            root, sequence, locked, deadline, _signatures(RESERVE_PK_1, RESERVE_PK_2, digest)
        );
    }

    function _mint(bytes32 id, bytes32 root, address recipient, uint256 amount) private {
        uint64 deadline = _deadline();
        bytes32 digest = token.depositDigest(id, root, recipient, amount, deadline);
        token.mintFromEngi(
            id, root, recipient, amount, deadline, _signatures(BRIDGE_PK_1, BRIDGE_PK_2, digest)
        );
    }

    function _finalize(bytes32 withdrawalId, bytes32 engiEventId) private {
        uint64 deadline = _deadline();
        bytes32 digest = token.withdrawalDigest(withdrawalId, engiEventId, deadline);
        token.finalizeWithdrawal(
            withdrawalId, engiEventId, deadline, _signatures(BRIDGE_PK_1, BRIDGE_PK_2, digest)
        );
    }

    function _deadline() private view returns (uint64) {
        return uint64(block.timestamp + 1 days);
    }

    function _signatures(uint256 privateKey1, uint256 privateKey2, bytes32 digest)
        private
        returns (bytes[] memory signatures)
    {
        bytes memory signature1 = _signature(privateKey1, digest);
        bytes memory signature2 = _signature(privateKey2, digest);
        signatures = new bytes[](2);
        if (vm.addr(privateKey1) < vm.addr(privateKey2)) {
            signatures[0] = signature1;
            signatures[1] = signature2;
        } else {
            signatures[0] = signature2;
            signatures[1] = signature1;
        }
    }

    function _signature(uint256 privateKey, bytes32 digest) private returns (bytes memory) {
        (uint8 v, bytes32 r, bytes32 s) = vm.sign(privateKey, digest);
        return abi.encodePacked(r, s, v);
    }
}
