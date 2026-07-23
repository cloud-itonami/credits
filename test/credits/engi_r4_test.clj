(ns credits.engi-r4-test
  (:require [clojure.test :refer [deftest is testing]]
            [credits.engi.codec :as codec]
            [credits.engi.commons :as commons]
            [credits.engi.crypto :as crypto]
            [credits.engi.standing :as standing]))

(def jury
  ["did:j1" "did:j2" "did:j3" "did:j4" "did:j5" "did:j6" "did:j7"
   "did:a1" "did:a2" "did:a3" "did:a4" "did:a5" "did:a6" "did:a7"
   "did:a8" "did:a9" "did:issuer-local" "did:issuer-independent"])
(def keys-by-did (zipmap jury (repeatedly crypto/generate-keypair)))
(defn public-key [did] (get-in keys-by-did [did :public-key]))

(defn sign-statements [event statements]
  (mapv (fn [statement]
          (crypto/sign-evidence
           event statement
           (get-in keys-by-did [(:signer statement) :private-key])))
        statements))

(defn decision [stage jurors choices]
  (let [roles (cycle [:local :technical :commons-guardian :external])
        statements
        (mapv (fn [juror role choice]
                {:signer juror :role role :choice choice})
              jurors roles choices)
        event (codec/with-event-id
               {:type :commons-decision :stage stage
                :votes statements})]
    (assoc event :votes (sign-statements event statements))))

(deftest standing-is-plural-and-nullifier-prevents-repeat-claim
  (let [statements
        [{:signer "did:issuer-local" :issuer-role :local-community}
         {:signer "did:issuer-independent"
          :issuer-role :independent-issuer}]
        claim (codec/with-event-id
               {:type :standing-claim :subject "did:alice"
                :epoch "2026-07" :nullifier "zk-nullifier-1"
                :credentials statements})
        signed (assoc claim :credentials (sign-statements claim statements))
        accepted (standing/verify-standing signed "2026-07" #{} public-key)]
    (is (:ok? accepted))
    (is (= :duplicate-standing-nullifier
           (:error
            (standing/verify-standing
             (assoc signed :subject "did:alice-shadow")
             "2026-07" (:used-nullifiers accepted) public-key))))
    (testing "ordinary exchange is intentionally outside this gate"
      (is (nil? (:balance signed))))))

(deftest basic-kisha-is-equal-and-does-not-accept-a-score
  (is (= {:allocations {"did:alice" 33 "did:bob" 33 "did:carol" 33}
          :remainder 1}
         (standing/equal-kisha
          100 ["did:carol" "did:alice" "did:bob"]))))

(deftest challenged-commons-proposal-needs-jury-and-independent-appeal
  (let [initial
        (commons/propose
         {:recipient "did:carer" :amount 50 :epoch "2026-07"
          :epoch-cap 100 :purpose :care
          :evidence-cids ["bafy-care"] :challenge-closes-at 100})
        challenged
        (:case
         (commons/challenge
          initial {:challenger "did:resident" :reason :evidence-disputed}))
        approve
        (decision :initial
                  ["did:j1" "did:j2" "did:j3" "did:j4"
                   "did:j5" "did:j6" "did:j7"]
                  [:approve :approve :approve :approve :approve
                   :reject :reject])
        decided (commons/decide challenged approve public-key)]
    (is (= :challenged (:status challenged)))
    (is (:ok? decided))
    (is (= :approved (get-in decided [:case :status])))
    (is (some? (commons/authorized-issuance (:case decided) [])))
    (let [reject-on-appeal
          (decision :appeal
                    ["did:a1" "did:a2" "did:a3" "did:a4" "did:a5"
                     "did:a6" "did:a7" "did:a8" "did:a9"]
                    [:reject :reject :reject :reject :reject :reject
                     :approve :approve :approve])
          appealed
          (commons/decide (:case decided) reject-on-appeal public-key)]
      (is (:ok? appealed))
      (is (= :rejected (get-in appealed [:case :status])))
      (is (nil? (commons/authorized-issuance (:case appealed) []))))))

(deftest appeal-cannot-reuse-the-first-jury
  (let [case (commons/propose
              {:recipient "did:carer" :amount 10 :epoch "e"
               :epoch-cap 20 :purpose :care :evidence-cids []
               :challenge-closes-at 1})
        first-jurors ["did:j1" "did:j2" "did:j3" "did:j4"
                      "did:j5" "did:j6" "did:j7"]
        first-decision
        (decision :initial first-jurors
                  (repeat 7 :approve))
        decided (:case (commons/decide case first-decision public-key))
        reused
        (decision :appeal
                  ["did:j1" "did:a2" "did:a3" "did:a4" "did:a5"
                   "did:a6" "did:a7" "did:a8" "did:a9"]
                  (repeat 9 :reject))]
    (is (= :appeal-jury-not-independent
           (:error (commons/decide decided reused public-key))))))
