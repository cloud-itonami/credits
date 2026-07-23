(ns credits.engi.crypto
  "Ed25519 device keys and ENGI evidence signatures.

  Key generation is for clients and tests. Production recovery rotates device
  keys through signed journal events; no private key is sent to a relay."
  (:require [credits.engi.codec :as codec])
  (:import (java.security KeyFactory KeyPairGenerator Signature)
           (java.security.spec PKCS8EncodedKeySpec X509EncodedKeySpec)))

(defn generate-keypair []
  (let [pair (.generateKeyPair (KeyPairGenerator/getInstance "Ed25519"))]
    {:public-key (codec/base64url (.getEncoded (.getPublic pair)))
     :private-key (codec/base64url (.getEncoded (.getPrivate pair)))}))

(defn- public-key [encoded]
  (.generatePublic
   (KeyFactory/getInstance "Ed25519")
   (X509EncodedKeySpec. (codec/base64url-decode encoded))))

(defn- private-key [encoded]
  (.generatePrivate
   (KeyFactory/getInstance "Ed25519")
   (PKCS8EncodedKeySpec. (codec/base64url-decode encoded))))

(defn sign-bytes [private-key-encoded payload]
  (let [signer (Signature/getInstance "Ed25519")]
    (.initSign signer (private-key private-key-encoded))
    (.update signer ^bytes payload)
    (codec/base64url (.sign signer))))

(defn verify-bytes? [public-key-encoded payload signature]
  (try
    (let [verifier (Signature/getInstance "Ed25519")]
      (.initVerify verifier (public-key public-key-encoded))
      (.update verifier ^bytes payload)
      (.verify verifier (codec/base64url-decode signature)))
    (catch Exception _ false)))

(defn sign-evidence [event evidence private-key-encoded]
  (assoc evidence
         :event-id (:id event)
         :signature
         (sign-bytes private-key-encoded
                     (codec/evidence-payload event evidence))))

(defn verify-evidence?
  "Resolve a signer DID/device id to its encoded Ed25519 public key."
  [resolve-public-key event evidence]
  (and (= (:event-id evidence) (:id event))
       (string? (:signature evidence))
       (when-let [key (resolve-public-key (:signer evidence))]
         (verify-bytes? key
                        (codec/evidence-payload event evidence)
                        (:signature evidence)))))
