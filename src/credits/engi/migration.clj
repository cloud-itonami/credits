(ns credits.engi.migration
  "Opt-in legacy-credit migration.

  A legacy balance is evidence for a Commons claim, never an EN opening mint.
  No claim changes balances until ordinary Commons governance approves a
  bounded issuance."
  (:require [credits.engi.codec :as codec]
            [credits.engi.crypto :as crypto]))

(defn consent-event
  "Unsigned template. The participant signs the sole `:consents` statement
  with the same local evidence mechanism used for ENGI events."
  [{:keys [subject legacy-ledger-root consented-at]}]
  (codec/with-event-id
   {:type :legacy-export-consent
    :subject subject
    :legacy-ledger-root legacy-ledger-root
    :scope :evidence-only-no-automatic-en
    :consented-at consented-at
    :consents [{:signer subject :role :participant-consent}]}))

(defn valid-consent?
  [consent subject evidence-cids resolve-public-key]
  (and (codec/valid-event-id? consent)
       (= :legacy-export-consent (:type consent))
       (= subject (:subject consent))
       (= :evidence-only-no-automatic-en (:scope consent))
       (some #{(:legacy-ledger-root consent)} evidence-cids)
       (= 1 (count (:consents consent)))
       (let [statement (first (:consents consent))]
         (and (= subject (:signer statement))
              (= :participant-consent (:role statement))
              (crypto/verify-evidence?
               resolve-public-key consent statement)))))

(defn export-claim
  [{:keys [subject legacy-balance evidence-cids consent exported-at]}
   resolve-public-key]
  (cond
    (or (not (string? subject))
        (not (nat-int? legacy-balance))
        (empty? evidence-cids))
    {:ok? false :error :invalid-legacy-claim}

    (not (valid-consent? consent subject evidence-cids resolve-public-key))
    {:ok? false :error :cryptographic-participant-consent-required}

    :else
    {:ok? true
     :claim
     (codec/with-event-id
      {:type :legacy-opening-claim
       :subject subject
       :legacy-balance legacy-balance
       :evidence-cids (vec (sort (conj evidence-cids (:id consent))))
       :consent-event-id (:id consent)
       :exported-at exported-at
       :status :pending-commons-review
       :monetary-effect 0})}))

(defn reconcile-claims
  "Create an auditable, non-monetary reconciliation summary. Duplicate
  subjects or malformed claims fail closed."
  [legacy-ledger-root claims]
  (let [subjects (map :subject claims)
        valid? (every?
                #(and (codec/valid-event-id? %)
                      (= :legacy-opening-claim (:type %))
                      (= 0 (:monetary-effect %))
                      (= :pending-commons-review (:status %))
                      (some #{legacy-ledger-root} (:evidence-cids %))
                      (string? (:consent-event-id %)))
                claims)]
    (cond
      (not valid?)
      {:ok? false :error :invalid-reconciliation-claim}

      (not= (count subjects) (count (set subjects)))
      {:ok? false :error :duplicate-participant-claim}

      :else
      {:ok? true
       :legacy-ledger-root legacy-ledger-root
       :participant-count (count claims)
       :total-legacy-evidence (reduce + (map :legacy-balance claims))
       :automatic-en-effect 0
       :claim-ids (vec (sort (map :id claims)))})))

(defn dispute [claim {:keys [challenger reason evidence-cids]}]
  {:ok? true
   :claim (assoc claim
                 :status :disputed
                 :dispute {:challenger challenger
                           :reason reason
                           :evidence-cids (vec (sort evidence-cids))})})

(defn to-commons-proposal-input
  "The approved amount is intentionally supplied by Commons deliberation and
  may differ from the legacy number. This function never auto-converts."
  [claim approved-amount epoch epoch-cap challenge-closes-at]
  {:recipient (:subject claim)
   :amount approved-amount
   :epoch epoch
   :epoch-cap epoch-cap
   :purpose :legacy-transition
   :evidence-cids (conj (:evidence-cids claim) (:id claim))
   :challenge-closes-at challenge-closes-at})
