#!/usr/bin/env node
// Independent Node.js verifier for ENGI canonical encoding and Ed25519.
// No package dependencies and no network access.

import {
  createHash,
  createPublicKey,
  verify as verifySignature,
} from "node:crypto";

function keyword(key) {
  return key.startsWith(":") ? key : `:${key}`;
}

export function canonicalEdn(value) {
  if (value === null) return "nil";
  if (value === true) return "true";
  if (value === false) return "false";
  if (typeof value === "string") {
    return value.startsWith(":") ? value : JSON.stringify(value);
  }
  if (typeof value === "number" && Number.isSafeInteger(value)) {
    return String(value);
  }
  if (Array.isArray(value)) {
    return `[${value.map(canonicalEdn).join(" ")}]`;
  }
  if (typeof value === "object") {
    const entries = Object.entries(value)
      .map(([key, item]) => [keyword(key), item])
      .sort(([a], [b]) => a.localeCompare(b));
    return `{${entries
      .map(([key, item]) => `${key} ${canonicalEdn(item)}`)
      .join(", ")}}`;
  }
  throw new Error("outside canonical ENGI value domain");
}

function stripEvidence(value) {
  if (Array.isArray(value)) return value.map(stripEvidence);
  if (value && typeof value === "object") {
    return Object.fromEntries(
      Object.entries(value)
        .filter(([key]) => !["signature", "event-id", "proof"].includes(key))
        .map(([key, item]) => [key, stripEvidence(item)]),
    );
  }
  return value;
}

export function eventId(event) {
  const { id: _id, ...body } = stripEvidence(event);
  body["protocol/version"] = 1;
  const bytes = Buffer.from(canonicalEdn(body), "utf8");
  return `en1:${createHash("sha256").update(bytes).digest("hex")}`;
}

export function verifyEd25519(publicKeyBase64url, payload, signatureBase64url) {
  const publicKey = createPublicKey({
    key: Buffer.from(publicKeyBase64url, "base64url"),
    format: "der",
    type: "spki",
  });
  return verifySignature(
    null,
    payload,
    publicKey,
    Buffer.from(signatureBase64url, "base64url"),
  );
}

const command = process.argv[2];
if (command === "vector") {
  const fixture = {
    type: ":transfer",
    from: "did:a",
    to: "did:b",
    amount: 7,
    nonce: 1,
    parents: [],
    signatures: [{ signer: "did:a" }, { signer: "did:b" }],
  };
  process.stdout.write(`${eventId(fixture)}\n`);
} else if (command === "verify") {
  const [, , , publicKey, payload, signature] = process.argv;
  process.stdout.write(
    `${verifyEd25519(publicKey, Buffer.from(payload, "base64url"), signature)}\n`,
  );
} else if (command) {
  process.stderr.write("usage: engi-verify.mjs vector|verify <key> <payload> <sig>\n");
  process.exitCode = 2;
}
