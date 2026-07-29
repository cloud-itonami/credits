// Browser-side WebAuthn assertion acquisition for canonical ENGI evidence.
// This module has no Node or package dependencies.

const bytesToBase64url = (value) => {
  const bytes = new Uint8Array(value);
  let binary = "";
  for (const byte of bytes) binary += String.fromCharCode(byte);
  return btoa(binary)
    .replaceAll("+", "-")
    .replaceAll("/", "_")
    .replace(/=+$/u, "");
};

const base64urlToBytes = (value) => {
  const base64 = value.replaceAll("-", "+").replaceAll("_", "/");
  const padded = base64.padEnd(Math.ceil(base64.length / 4) * 4, "=");
  return Uint8Array.from(atob(padded), (character) => character.charCodeAt(0));
};

export async function evidenceChallenge(evidencePayload) {
  return new Uint8Array(await crypto.subtle.digest("SHA-256", evidencePayload));
}

export async function requestEvidenceAssertion({
  credentialId,
  evidencePayload,
  rpId,
  timeout = 60_000,
}) {
  if (!globalThis.isSecureContext || !navigator.credentials) {
    throw new Error("WebAuthn requires a secure browser context");
  }
  const credential = await navigator.credentials.get({
    publicKey: {
      challenge: await evidenceChallenge(evidencePayload),
      rpId,
      allowCredentials: [{
        id: base64urlToBytes(credentialId),
        type: "public-key",
        transports: ["internal", "hybrid", "usb", "nfc", "ble"],
      }],
      userVerification: "required",
      timeout,
    },
  });
  if (!credential) throw new Error("WebAuthn assertion was not returned");
  return {
    credentialId: bytesToBase64url(credential.rawId),
    authenticatorData:
      bytesToBase64url(credential.response.authenticatorData),
    clientDataJSON: bytesToBase64url(credential.response.clientDataJSON),
    signature: bytesToBase64url(credential.response.signature),
  };
}
