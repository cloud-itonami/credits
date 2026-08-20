(ns credits.engi.webauthn
  "Local WebAuthn ES256 assertion verification for ENGI evidence.

  Registration supplies credential id and SPKI public key. No online identity
  provider or etzhayyim service participates in assertion verification."
  (:require [json.compat :as json]
            [credits.engi.codec :as codec])
  (:import (java.nio.charset StandardCharsets)
           (java.security KeyFactory MessageDigest Signature)
           (java.security.spec X509EncodedKeySpec)))

(defn- sha256 [^bytes value]
  (.digest (MessageDigest/getInstance "SHA-256") value))

(defn- concat-bytes [^bytes left ^bytes right]
  (let [result (byte-array (+ (alength left) (alength right)))]
    (System/arraycopy left 0 result 0 (alength left))
    (System/arraycopy right 0 result (alength left) (alength right))
    result))

(defn- public-key [encoded]
  (.generatePublic
   (KeyFactory/getInstance "EC")
   (X509EncodedKeySpec. (codec/base64url-decode encoded))))

(defn verify-assertion?
  [{:keys [credential-id public-key-spki origin rp-id]} payload assertion]
  (try
    (let [client-bytes (codec/base64url-decode (:client-data-json assertion))
          client (json/parse-string
                  (String. client-bytes StandardCharsets/UTF_8))
          authenticator (codec/base64url-decode
                         (:authenticator-data assertion))
          flags (when (<= 33 (alength authenticator))
                  (bit-and 0xff (aget authenticator 32)))
          rp-hash (when (<= 32 (alength authenticator))
                    (java.util.Arrays/copyOfRange authenticator 0 32))
          challenge (codec/base64url (sha256 payload))
          signed (concat-bytes authenticator (sha256 client-bytes))
          verifier (Signature/getInstance "SHA256withECDSA")]
      (and (= credential-id (:credential-id assertion))
           (= "webauthn.get" (get client "type"))
           (= challenge (get client "challenge"))
           (= origin (get client "origin"))
           (not= true (get client "crossOrigin"))
           (= (seq (sha256 (.getBytes ^String rp-id StandardCharsets/UTF_8)))
              (seq rp-hash))
           (some? flags)
           (pos? (bit-and flags 0x01))
           (pos? (bit-and flags 0x04))
           (do
             (.initVerify verifier (public-key public-key-spki))
             (.update verifier signed)
             (.verify verifier
                      (codec/base64url-decode (:signature assertion))))))
    (catch Exception _ false)))
