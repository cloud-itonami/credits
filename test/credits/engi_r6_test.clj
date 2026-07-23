(ns credits.engi-r6-test
  (:require [clojure.java.shell :as shell]
            [clojure.test :refer [deftest is]]
            [credits.engi.audit :as audit]
            [credits.engi.benchmark :as benchmark]
            [credits.engi.codec :as codec]
            [credits.engi.crypto :as crypto]))

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

(deftest audit-fails-closed-on-central-writer-and-invalid-state
  (let [manifest (slurp "actor-manifest.jsonld")
        manifest-audit (audit/audit-legacy-manifest manifest)]
    (is (:ok? manifest-audit))
    (is (not (:write-capability? manifest-audit))))
  (let [invalid {:balances {"did:a" 1}
                 :credit-lines {} :accepted-events []
                 :commons-issued-by-epoch {}}]
    (is (not (:ok? (audit/audit-state invalid))))))

(deftest replay-core-benchmark-preserves-conservation
  (let [result (benchmark/run-transfers 10000)]
    (is (:valid-state? result))
    (is (= -10000 (:alice-balance result)))
    (is (= 10000 (:bob-balance result)))
    (is (pos? (:events-per-second result)))))
