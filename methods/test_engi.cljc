(ns credits.methods.test-engi
  (:require [clojure.test :refer [deftest is testing]]
            [credits.methods.engi :as engi]))

(defn signature-ok? [event signature]
  (= (:event-id signature) (:id event)))

(defn attestation-ok? [event attestation]
  (= (:event-id attestation) (:id event)))

(defn endorsement-ok? [request endorsement]
  (= (:subject request) (:subject endorsement)))

(defn line [state did limits]
  (:state
   (engi/establish-credit-line
    state
    {:id (str "credit-line:" did) :type :credit-line :subject did
     :endorsements (mapv (fn [[guarantor limit]]
                           {:guarantor guarantor :limit limit :subject did})
                         limits)}
    endorsement-ok?)))

(defn transfer [id from to amount nonce]
  {:id id :type :transfer :from from :to to :amount amount :nonce nonce
   :parents [(str "credit-line:" from)]
   :signatures [{:signer from :event-id id}
                {:signer to :event-id id}]})

(deftest bilateral-exchange-creates-no-net-supply
  (let [s0 (-> engi/zero-state
               (line "did:alice" [["did:bob" 100] ["did:carol" 80]])
               (line "did:bob" [["did:alice" 70] ["did:carol" 90]]))
        r1 (engi/apply-transfer s0 (transfer "tx-1" "did:alice" "did:bob" 60 1)
                                signature-ok?)
        s1 (:state r1)
        r2 (engi/apply-transfer s1 (transfer "tx-2" "did:bob" "did:alice" 20 1)
                                signature-ok?)
        s2 (:state r2)]
    (is (:ok? r1))
    (is (:ok? r2))
    (is (= -40 (engi/balance-of s2 "did:alice")))
    (is (= 40 (engi/balance-of s2 "did:bob")))
    (is (zero? (engi/mutual-credit-net s2)))
    (is (= #{"did:alice" "did:bob"}
           (set (map :signer (:signatures (last (:accepted-events s2)))))))
    (is (engi/valid-state? s2))))

(deftest no-central-or-unilateral-issuance
  (let [s (line engi/zero-state "did:alice"
                [["did:bob" 50] ["did:carol" 50]])]
    (testing "the receiver must countersign"
      (is (= :bilateral-signature-required
             (:error (engi/apply-transfer
                      s
                      (assoc (transfer "tx-1" "did:alice" "did:bob" 10 1)
                             :signatures [{:signer "did:alice" :event-id "tx-1"}])
                      signature-ok?)))))
    (testing "a replay cannot spend twice"
      (let [s1 (:state (engi/apply-transfer
                        s (transfer "tx-1" "did:alice" "did:bob" 10 1)
                        signature-ok?))]
        (is (= :replayed-nonce
               (:error (engi/apply-transfer
                        s1 (transfer "tx-2" "did:alice" "did:carol" 10 1)
                        signature-ok?))))))
    (testing "relational credit is bounded"
      (is (= :credit-limit-exceeded
             (:error (engi/apply-transfer
                      s (transfer "tx-big" "did:alice" "did:bob" 51 1)
                      signature-ok?)))))))

(deftest credit-limit-needs-independent-relations
  (is (= :insufficient-independent-guarantors
         (:error
          (engi/establish-credit-line
           engi/zero-state
           {:id "credit-line:alice" :type :credit-line :subject "did:alice"
            :endorsements [{:guarantor "did:alice" :limit 999999
                            :subject "did:alice"}
                           {:guarantor "did:bob" :limit 100
                            :subject "did:alice"}]}
           endorsement-ok?))))
  (is (= 80
         (-> engi/zero-state
             (line "did:alice" [["did:bob" 50]
                                ["did:carol" 80]
                                ["did:dave" 100]])
             (engi/credit-limit-of "did:alice")))))

(def commons-attestations
  [{:signer "did:local-1" :role :local-community :event-id "commons-1"}
   {:signer "did:local-2" :role :local-community :event-id "commons-1"}
   {:signer "did:survey" :role :independent-witness :event-id "commons-1"}
   {:signer "did:river" :role :commons-guardian :event-id "commons-1"}])

(deftest commons-issuance-is-bounded-and-heterogeneous
  (let [event {:id "commons-1" :type :commons-issuance
               :recipient "did:carer" :amount 30 :epoch "2026-07"
               :epoch-cap 100 :attestations commons-attestations}
        result (engi/apply-commons-issuance engi/zero-state event
                                            attestation-ok?)
        state (:state result)]
    (is (:ok? result))
    (is (= 30 (engi/balance-of state "did:carer")))
    (is (= 4 (count (:attestations (last (:accepted-events state))))))
    (is (zero? (engi/mutual-credit-net state)))
    (is (engi/valid-state? state))
    (is (= :commons-epoch-cap-exceeded
           (:error
            (engi/apply-commons-issuance
             state (assoc event :id "commons-2" :amount 80
                          :attestations
                          (mapv #(assoc % :event-id "commons-2")
                                commons-attestations))
             attestation-ok?))))
    (is (= :heterogeneous-commons-quorum-required
           (:error
            (engi/apply-commons-issuance
             engi/zero-state
             (assoc event :attestations (subvec commons-attestations 0 3))
             attestation-ok?))))))

(deftest journal-evidence-and-identifiers-are-replay-safe
  (let [line-request
        {:id "credit-line:alice" :type :credit-line :subject "did:alice"
         :endorsements [{:guarantor "did:bob" :limit 50
                         :subject "did:alice"}
                        {:guarantor "did:carol" :limit 50
                         :subject "did:alice"}]}
        established (engi/establish-credit-line
                     engi/zero-state line-request endorsement-ok?)
        state (:state established)]
    (is (:ok? established))
    (is (= (:endorsements line-request)
           (:endorsements (first (:accepted-events state)))))
    (is (= :replayed-event-id
           (:error (engi/establish-credit-line
                    state line-request endorsement-ok?))))
    (is (= :invalid-nonce
           (:error (engi/apply-transfer
                    state
                    (transfer "tx-no-nonce" "did:alice" "did:bob" 1 nil)
                    signature-ok?))))))
