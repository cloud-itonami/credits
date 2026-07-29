#!/usr/bin/env node
// Independent Node verifier for WebAuthn ENGI evidence assertions.
// Standard Node APIs only; private keys never enter ENGI or a relay.

import {
  createHash,
  createPublicKey,
  generateKeyPairSync,
  sign as signBytes,
  verify as verifyBytes,
} from "node:crypto";
import { pathToFileURL } from "node:url";

const b64u = (value) => Buffer.from(value).toString("base64url");
const unb64u = (value) => Buffer.from(value, "base64url");
const sha256 = (value) => createHash("sha256").update(value).digest();

export function evidenceChallenge(payload) {
  return b64u(sha256(payload));
}

export function verifyEvidenceAssertion({
  assertion,
  evidencePayload,
  expectedCredentialId,
  expectedOrigin,
  expectedRpId,
  publicKeySpki,
  requireUserVerification = true,
}) {
  try {
    if (assertion.credentialId !== expectedCredentialId) return false;
    const clientDataBytes = unb64u(assertion.clientDataJSON);
    const clientData = JSON.parse(clientDataBytes.toString("utf8"));
    if (clientData.type !== "webauthn.get") return false;
    if (clientData.origin !== expectedOrigin) return false;
    if (clientData.challenge !== evidenceChallenge(evidencePayload)) return false;
    if (clientData.crossOrigin === true) return false;

    const authenticatorData = unb64u(assertion.authenticatorData);
    if (authenticatorData.length < 37) return false;
    if (!authenticatorData.subarray(0, 32).equals(sha256(expectedRpId))) {
      return false;
    }
    const flags = authenticatorData[32];
    const userPresent = (flags & 0x01) !== 0;
    const userVerified = (flags & 0x04) !== 0;
    if (!userPresent || (requireUserVerification && !userVerified)) return false;

    const signed = Buffer.concat([
      authenticatorData,
      sha256(clientDataBytes),
    ]);
    const publicKey = createPublicKey({
      key: unb64u(publicKeySpki),
      format: "der",
      type: "spki",
    });
    return verifyBytes(
      "sha256",
      signed,
      publicKey,
      unb64u(assertion.signature),
    );
  } catch {
    return false;
  }
}

function fixtureForPayload(evidencePayload) {
  const { publicKey, privateKey } = generateKeyPairSync("ec", {
    namedCurve: "P-256",
  });
  const expectedRpId = "wallet.example";
  const expectedOrigin = "https://wallet.example";
  const expectedCredentialId = b64u(Buffer.from("credential-1"));
  const clientDataBytes = Buffer.from(JSON.stringify({
    type: "webauthn.get",
    challenge: evidenceChallenge(evidencePayload),
    origin: expectedOrigin,
    crossOrigin: false,
  }));
  const authenticatorData = Buffer.concat([
    sha256(expectedRpId),
    Buffer.from([0x05]),
    Buffer.alloc(4),
  ]);
  const signature = signBytes(
    "sha256",
    Buffer.concat([authenticatorData, sha256(clientDataBytes)]),
    privateKey,
  );
  return {
    assertion: {
      credentialId: expectedCredentialId,
      authenticatorData: b64u(authenticatorData),
      clientDataJSON: b64u(clientDataBytes),
      signature: b64u(signature),
    },
    evidencePayload,
    expectedCredentialId,
    expectedOrigin,
    expectedRpId,
    publicKeySpki: b64u(publicKey.export({ format: "der", type: "spki" })),
  };
}

function selfTest() {
  const fixture = fixtureForPayload(
    Buffer.from("canonical ENGI evidence", "utf8"),
  );
  return {
    valid: verifyEvidenceAssertion(fixture),
    wrongOriginRejected: !verifyEvidenceAssertion({
      ...fixture,
      expectedOrigin: "https://attacker.example",
    }),
    wrongPayloadRejected: !verifyEvidenceAssertion({
      ...fixture,
      evidencePayload: Buffer.from("modified evidence"),
    }),
    wrongRpRejected: !verifyEvidenceAssertion({
      ...fixture,
      expectedRpId: "attacker.example",
    }),
  };
}

if (typeof process !== "undefined"
    && process.argv[1]
    && import.meta.url === pathToFileURL(process.argv[1]).href) {
  if (process.argv[2] === "self-test") {
    process.stdout.write(`${JSON.stringify(selfTest())}\n`);
  } else if (process.argv[2] === "fixture" && process.argv[3]) {
    const fixture = fixtureForPayload(unb64u(process.argv[3]));
    process.stdout.write(`${JSON.stringify({
      assertion: fixture.assertion,
      credentialId: fixture.expectedCredentialId,
      origin: fixture.expectedOrigin,
      rpId: fixture.expectedRpId,
      publicKeySpki: fixture.publicKeySpki,
    })}\n`);
  } else {
    process.stderr.write(
      "usage: engi-webauthn.mjs self-test|fixture <payload>\n",
    );
    process.exitCode = 2;
  }
}
