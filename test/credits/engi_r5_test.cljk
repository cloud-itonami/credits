(ns credits.engi-r5-test
  (:require [clojure.test :refer [deftest is testing]]
            [credits.engi.crypto :as crypto]
            [credits.engi.migration :as migration]
            [credits.engi.netting :as netting]))

(deftest living-basket-needs-diverse-regional-observers
  (let [observations
        [{:region "kanto" :epoch "2026-07" :source "coop-a"
          :micro-en 100}
         {:region "kanto" :epoch "2026-07" :source "coop-b"
          :micro-en 110}
         {:region "kanto" :epoch "2026-07" :source "household-c"
          :micro-en 90}]
        index (netting/basket-index "kanto" "2026-07" observations)]
    (is (:ok? index))
    (is (= 100 (:micro-en-per-basket index)))
    (is (= :source-diversity-required
           (:error
            (netting/basket-index
             "kanto" "2026-07" (take 2 observations)))))))

(deftest cross-region-conversion-discloses-rounding
  (let [from {:epoch "e" :micro-en-per-basket 3}
        to {:epoch "e" :micro-en-per-basket 2}
        result (netting/convert 10 from to)]
    (is (= 6 (:amount result)))
    (is (= 2 (:rounding-numerator result)))
    (is (= 3 (:rounding-denominator result)))))

(deftest multilateral-netting-conserves-regional-value
  (let [obligations
        [{:id "o1" :from-region "a" :to-region "b" :amount 10}
         {:id "o2" :from-region "b" :to-region "c" :amount 7}
         {:id "o3" :from-region "c" :to-region "a" :amount 4}]
        proposal (netting/netting-proposal "e" obligations)]
    (is (= {"a" -6 "b" 3 "c" 3} (:positions proposal)))
    (is (zero? (reduce + (vals (:positions proposal)))))))

(deftest legacy-balance-is-opt-in-evidence-never-automatic-en
  (let [keys (crypto/generate-keypair)
        public-key (fn [did]
                     (when (= did "did:alice") (:public-key keys)))
        template (migration/consent-event
                  {:subject "did:alice"
                   :legacy-ledger-root "bafy-ledger-export"
                   :consented-at "2026-07-23T12:00:00Z"})
        consent (update template :consents
                        (fn [[statement]]
                          [(crypto/sign-evidence
                            template statement (:private-key keys))]))
        input {:subject "did:alice" :legacy-balance 700
               :evidence-cids ["bafy-ledger-export"]
               :consent consent :exported-at "2026-07-23"}
        result (migration/export-claim input public-key)
        claim (:claim result)]
    (is (:ok? result))
    (is (= 0 (:monetary-effect claim)))
    (is (= :pending-commons-review (:status claim)))
    (is (= :cryptographic-participant-consent-required
           (:error (migration/export-claim
                    (assoc input :consent nil) public-key))))
    (is (= :cryptographic-participant-consent-required
           (:error (migration/export-claim
                    (assoc-in input [:consent :legacy-ledger-root]
                              "bafy-other")
                    public-key))))
    (is (= 0 (:automatic-en-effect
              (migration/reconcile-claims
               "bafy-ledger-export" [claim]))))
    (is (= :duplicate-participant-claim
           (:error (migration/reconcile-claims
                    "bafy-ledger-export" [claim claim]))))
    (testing "Commons decides a bounded amount; 700 is not auto-minted"
      (let [proposal
            (migration/to-commons-proposal-input
             claim 50 "2026-08" 100 42)]
        (is (= 50 (:amount proposal)))
        (is (= :legacy-transition (:purpose proposal)))))))

(deftest legacy-central-writer-is-disabled
  (let [manifest (slurp "actor-manifest.jsonld")]
    (is (re-find #"\"enabled\"\s*:\s*false" manifest))
    (is (re-find #"\"legacyExecutionTier\"\s*:\s*\"T3\"" manifest))
    (is (not (re-find #"\"capabilities\"\s*:\s*\[[^\]]*\"graph.write\""
                      manifest)))))
