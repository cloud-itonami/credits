(ns credits.engi-r2-test
  (:require [clojure.test :refer [deftest is testing]]
            [credits.engi.codec :as codec]
            [credits.engi.crypto :as crypto]
            [credits.engi.identity :as identity]
            [credits.engi.journal :as journal]
            [credits.engi.replay :as replay]))

(def participants
  ["did:alice" "did:bob" "did:carol" "did:guardian-1"
   "did:guardian-2" "did:guardian-3"])

(def keys-by-did
  (zipmap participants (repeatedly crypto/generate-keypair)))

(defn public-key [did]
  (get-in keys-by-did [did :public-key]))

(defn signed-event [body evidence-key evidence]
  (let [event (codec/with-event-id (assoc body evidence-key evidence))]
    (assoc event evidence-key
           (mapv (fn [statement]
                   (crypto/sign-evidence
                    event statement
                    (get-in keys-by-did
                            [(:signer statement) :private-key])))
                 evidence))))

(def line-event
  (signed-event
   {:type :credit-line :subject "did:alice" :parents []}
   :endorsements
   [{:signer "did:bob" :guarantor "did:bob"
     :subject "did:alice" :limit 100}
    {:signer "did:carol" :guarantor "did:carol"
     :subject "did:alice" :limit 80}]))

(def transfer-event
  (signed-event
   {:type :transfer :from "did:alice" :to "did:bob"
    :amount 25 :nonce 1 :parents [(:id line-event)]}
   :signatures [{:signer "did:alice"} {:signer "did:bob"}]))

(defn append! [j event]
  (:journal (journal/append-event j event)))

(deftest three-offline-devices-converge-without-relay-authority
  (let [alice (append! (journal/new-journal "did:alice" "phone-a")
                       line-event)
        bob (append! (journal/new-journal "did:bob" "phone-b")
                     transfer-event)
        carol (append! (journal/new-journal "did:carol" "tablet-c")
                       line-event)
        order-a (journal/merge-and-replay [alice bob carol] public-key)
        order-b (journal/merge-and-replay [carol alice bob] public-key)]
    (is (:ok? order-a))
    (is (= 2 (:merged-event-count order-a)))
    (is (= (:state-root order-a) (:state-root order-b)))
    (is (= -25 (get-in order-a [:state :balances "did:alice"])))
    (is (= 25 (get-in order-a [:state :balances "did:bob"])))))

(deftest concurrent-offline-spend-is-quarantined-not-picked-by-arrival
  (let [other (signed-event
               {:type :transfer :from "did:alice" :to "did:carol"
                :amount 20 :nonce 1 :parents [(:id line-event)]}
               :signatures [{:signer "did:alice"} {:signer "did:carol"}])
        a (-> (journal/new-journal "did:alice" "phone-a")
              (append! line-event)
              (append! transfer-event))
        b (append! (journal/new-journal "did:carol" "tablet-c") other)
        merged (journal/merge-journals [a b])]
    (is (not (:ok? merged)))
    (is (= :concurrent-spend-conflict (:error merged)))
    (is (= #{(:id transfer-event) (:id other)}
           (set (get-in merged [:conflicts 0 :event-ids]))))))

(deftest missing-dependency-and-id-collision-fail-closed
  (let [only-transfer (append! (journal/new-journal "did:bob" "phone-b")
                               transfer-event)]
    (is (= :missing-parent
           (:error (journal/merge-journals [only-transfer])))))
  (let [tampered (assoc transfer-event :amount 999)
        j {:owner-did "did:x" :device-id "x"
           :events {(:id transfer-event) tampered}}
        good (append! (journal/new-journal "did:bob" "b") transfer-event)]
    (is (= :event-id-mismatch
           (:error (journal/merge-journals [j good]))))))

(deftest guardian-recovery-rotates-without-a-central-key-holder
  (let [old-key (crypto/generate-keypair)
        new-key (crypto/generate-keypair)
        state (identity/identity-state
               {:did "did:alice"
                :active-public-key (:public-key old-key)
                :guardians ["did:guardian-1" "did:guardian-2"
                            "did:guardian-3"]
                :threshold 2})
        unsigned (identity/rotation-event state (:public-key new-key))
        attestations
        (mapv
         (fn [statement]
           (if (#{"did:guardian-1" "did:guardian-2"} (:signer statement))
             (crypto/sign-evidence
              unsigned statement
              (get-in keys-by-did [(:signer statement) :private-key]))
             statement))
         (:attestations unsigned))
        event (assoc unsigned :attestations attestations)
        result (identity/apply-rotation state event public-key)]
    (is (:ok? result))
    (is (= (:public-key new-key)
           (get-in result [:identity :active-public-key])))
    (is (contains? (get-in result [:identity :revoked-public-keys])
                   (:public-key old-key)))
    (testing "the same recovery cannot be replayed"
      (is (= :stale-or-forked-key
             (:error
              (identity/apply-rotation
               (:identity result) event public-key)))))
    (testing "one guardian cannot seize an identity"
      (let [one (assoc unsigned :attestations
                       (mapv (fn [statement]
                               (if (= "did:guardian-1" (:signer statement))
                                 (crypto/sign-evidence
                                  unsigned statement
                                  (get-in keys-by-did
                                          ["did:guardian-1" :private-key]))
                                 statement))
                             (:attestations unsigned)))]
        (is (= :guardian-quorum-required
               (:error (identity/apply-rotation state one public-key))))))))

(deftest sequential-nonces-form-a-causal-parent-chain
  (let [second
        (signed-event
         {:type :transfer :from "did:alice" :to "did:bob"
          :amount 10 :nonce 2
          :parents [(:id line-event) (:id transfer-event)]}
         :signatures [{:signer "did:alice"} {:signer "did:bob"}])
        orphan
        (signed-event
         {:type :transfer :from "did:alice" :to "did:bob"
          :amount 10 :nonce 2 :parents [(:id line-event)]}
         :signatures [{:signer "did:alice"} {:signer "did:bob"}])
        valid (journal/merge-and-replay
               [(-> (journal/new-journal "did:alice" "phone")
                    (append! second)
                    (append! line-event)
                    (append! transfer-event))]
               public-key)
        invalid (replay/replay [line-event transfer-event orphan] public-key)]
    (is (:ok? valid))
    (is (= 2 (get-in valid [:state :next-nonce "did:alice"])))
    (is (not (:ok? invalid)))
    (is (= :previous-nonce-parent-required (:error invalid)))))

(deftest credit-line-revisions-join-the-participant-causal-chain
  (let [revised
        (signed-event
         {:type :credit-line :subject "did:alice"
          :revision 2 :after-nonce 1
          :parents [(:id line-event) (:id transfer-event)]}
         :endorsements
         [{:signer "did:bob" :guarantor "did:bob"
           :subject "did:alice" :limit 120}
          {:signer "did:carol" :guarantor "did:carol"
           :subject "did:alice" :limit 100}])
        competing
        (signed-event
         {:type :credit-line :subject "did:alice"
          :revision 2 :after-nonce 1
          :parents [(:id line-event) (:id transfer-event)]}
         :endorsements
         [{:signer "did:bob" :guarantor "did:bob"
           :subject "did:alice" :limit 90}
          {:signer "did:carol" :guarantor "did:carol"
           :subject "did:alice" :limit 90}])
        second
        (signed-event
         {:type :transfer :from "did:alice" :to "did:bob"
          :amount 10 :nonce 2
          :parents [(:id transfer-event) (:id revised)]}
         :signatures [{:signer "did:alice"} {:signer "did:bob"}])
        converged
        (journal/merge-and-replay
         [(-> (journal/new-journal "did:alice" "a")
              (append! second)
              (append! line-event))
          (-> (journal/new-journal "did:bob" "b")
              (append! revised)
              (append! transfer-event))]
         public-key)
        forked
        (journal/merge-journals
         [(-> (journal/new-journal "did:alice" "a")
              (append! revised))
          (-> (journal/new-journal "did:alice" "b")
              (append! competing))])]
    (is (:ok? converged))
    (is (= 2 (get-in converged
                     [:state :credit-line-revisions "did:alice"])))
    (is (= 120 (get-in converged
                       [:state :credit-lines "did:alice" :limit])))
    (is (= 2 (get-in converged [:state :next-nonce "did:alice"])))
    (is (= :concurrent-credit-line-conflict (:error forked)))))
