(ns credits.engi-r1-test
  (:require [clojure.test :refer [deftest is testing]]
            [credits.engi.codec :as codec]
            [credits.engi.crypto :as crypto]
            [credits.engi.replay :as replay]))

(def identities
  ["did:alice" "did:bob" "did:carol" "did:local-1"
   "did:local-2" "did:survey" "did:river" "did:carer"])

(def keys-by-did
  (zipmap identities (repeatedly crypto/generate-keypair)))

(defn public-key [did]
  (get-in keys-by-did [did :public-key]))

(defn sign [event evidence]
  (crypto/sign-evidence
   event evidence (get-in keys-by-did [(:signer evidence) :private-key])))

(defn signed-event [body evidence-key evidence]
  (let [event (codec/with-event-id (assoc body evidence-key evidence))]
    (assoc event evidence-key (mapv #(sign event %) evidence))))

(def credit-line
  (signed-event
   {:type :credit-line :subject "did:alice"}
   :endorsements
   [{:signer "did:bob" :guarantor "did:bob"
     :subject "did:alice" :limit 100}
    {:signer "did:carol" :guarantor "did:carol"
     :subject "did:alice" :limit 80}]))

(def transfer
  (signed-event
   {:type :transfer :from "did:alice" :to "did:bob"
    :amount 60 :nonce 1}
   :signatures
   [{:signer "did:alice"} {:signer "did:bob"}]))

(def commons
  (signed-event
   {:type :commons-issuance :recipient "did:carer"
    :amount 30 :epoch "2026-07" :epoch-cap 100}
   :attestations
   [{:signer "did:local-1" :role :local-community}
    {:signer "did:local-2" :role :local-community}
    {:signer "did:survey" :role :independent-witness}
    {:signer "did:river" :role :commons-guardian}]))

(deftest canonical-codec-is-stable-and-strict
  (is (= "{:a 1, :b 2}"
         (codec/canonical-string {:b 2 :a 1})))
  (is (= (codec/event-id {:b 2 :a 1})
         (codec/event-id {:a 1 :b 2})))
  (is (thrown? clojure.lang.ExceptionInfo
               (codec/canonical-string {:amount 1.5})))
  (is (thrown? clojure.lang.ExceptionInfo
               (codec/canonical-string #{:a :b}))))

(deftest real-ed25519-signatures-round-trip
  (let [evidence (first (:signatures transfer))]
    (is (crypto/verify-evidence? public-key transfer evidence))
    (is (not (crypto/verify-evidence?
              public-key (assoc transfer :amount 61) evidence)))
    (is (not (crypto/verify-evidence?
              (constantly (public-key "did:carol")) transfer evidence)))))

(deftest full-journal-replay-is-deterministic
  (let [journal [credit-line transfer commons]
        a (replay/replay journal public-key)
        b (replay/replay journal public-key)]
    (is (:ok? a))
    (is (= (:state-root a) (:state-root b)))
    (is (= -60 (get-in a [:state :balances "did:alice"])))
    (is (= 60 (get-in a [:state :balances "did:bob"])))
    (is (= 30 (get-in a [:state :balances "did:carer"])))
    (is (= 3 (count (get-in a [:state :accepted-events]))))))

(deftest tamper-reorder-gap-and-unknown-events-fail-closed
  (testing "economic content tampering changes the event id"
    (is (= :event-id-mismatch
           (:error (replay/replay
                    [credit-line (assoc transfer :amount 61)] public-key)))))
  (testing "a transfer cannot precede its credit relation"
    (is (= :credit-limit-exceeded
           (:error (replay/replay [transfer credit-line] public-key)))))
  (testing "payer nonce must be contiguous"
    (let [gap (signed-event
               {:type :transfer :from "did:alice" :to "did:bob"
                :amount 1 :nonce 2}
               :signatures
               [{:signer "did:alice"} {:signer "did:bob"}])]
      (is (= :non-contiguous-nonce
             (:error (replay/replay [credit-line gap] public-key))))))
  (testing "unknown protocol events are rejected"
    (let [event (codec/with-event-id {:type :central-mint :amount 1000})]
      (is (= :unknown-event-type
             (:error (replay/replay [event] public-key)))))))
