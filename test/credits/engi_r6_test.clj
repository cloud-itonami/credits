(ns credits.engi-r6-test
  (:require [clojure.java.shell :as shell]
            [clojure.test :refer [deftest is]]
            [credits.engi.audit :as audit]
            [credits.engi.benchmark :as benchmark]
            [credits.engi.codec :as codec]
            [credits.engi.crypto :as crypto]
            [credits.engi.replay :as replay]))

(deftest independent-node-client-matches-canonical-vector
  (let [event {:type :transfer :from "did:a" :to "did:b"
               :amount 7 :nonce 1 :parents []
               :signatures [{:signer "did:a"} {:signer "did:b"}]}
        expected (codec/event-id event)
        result (shell/sh "node" "clients/engi-verify.mjs" "vector")]
    (is (zero? (:exit result)))
    (is (= expected (.trim (:out result))))))

(deftest independent-node-client-verifies-clojure-ed25519
  (let [keypair (crypto/generate-keypair)
        payload (.getBytes "engi-cross-client" "UTF-8")
        signature (crypto/sign-bytes (:private-key keypair) payload)
        result
        (shell/sh "node" "clients/engi-verify.mjs" "verify"
                  (:public-key keypair)
                  (codec/base64url payload)
                  signature)]
    (is (zero? (:exit result)))
    (is (= "true" (.trim (:out result))))))

(deftest webauthn-assertion-binds-device-presence-to-evidence
  (let [result (shell/sh "node" "clients/engi-webauthn.mjs" "self-test")]
    (is (zero? (:exit result)))
    (is (= (str "{\"valid\":true,"
                "\"wrongOriginRejected\":true,"
                "\"wrongPayloadRejected\":true,"
                "\"wrongRpRejected\":true}")
           (.trim (:out result))))))

(deftest audit-fails-closed-on-central-writer-and-invalid-state
  (let [manifest (slurp "actor-manifest.jsonld")
        manifest-audit (audit/audit-legacy-manifest manifest)]
    (is (:ok? manifest-audit))
    (is (not (:write-capability? manifest-audit))))
  (let [invalid {:balances {"did:a" 1}
                 :credit-lines {} :accepted-events []
                 :commons-issued-by-epoch {}}]
    (is (not (:ok? (audit/audit-state invalid))))))

(deftest audit-reconstructs-state-instead-of-trusting-a-snapshot
  (let [actors ["did:a" "did:b" "did:g1" "did:g2"]
        keys (zipmap actors (repeatedly crypto/generate-keypair))
        resolve-key #(get-in keys [% :public-key])
        sign
        (fn [body evidence-key statements]
          (let [event (codec/with-event-id
                       (assoc body evidence-key statements))]
            (assoc event evidence-key
                   (mapv #(crypto/sign-evidence
                           event % (get-in keys [(:signer %) :private-key]))
                         statements))))
        line (sign
              {:type :credit-line :subject "did:a" :parents []}
              :endorsements
              [{:signer "did:g1" :guarantor "did:g1"
                :subject "did:a" :limit 20}
               {:signer "did:g2" :guarantor "did:g2"
                :subject "did:a" :limit 20}])
        transfer (sign
                  {:type :transfer :from "did:a" :to "did:b"
                   :amount 5 :nonce 1 :parents [(:id line)]}
                  :signatures
                  [{:signer "did:a"} {:signer "did:b"}])
        replayed (replay/replay [line transfer] resolve-key)
        valid (audit/audit-state-against-replay
               (:state replayed) resolve-key)
        forged (audit/audit-state-against-replay
                (-> (:state replayed)
                    (assoc-in [:balances "did:a"] -4)
                    (assoc-in [:balances "did:b"] 4))
                resolve-key)]
    (is (:ok? valid))
    (is (:cryptographic-replay-ok? valid))
    (is (not (:ok? forged)))
    (is (not (:state-matches-replay? forged)))))

(deftest replay-core-benchmark-preserves-conservation
  (let [result (benchmark/run-transfers 10000)]
    (is (:valid-state? result))
    (is (= -10000 (:alice-balance result)))
    (is (= 10000 (:bob-balance result)))
    (is (pos? (:events-per-second result)))))
