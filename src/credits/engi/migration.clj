(ns credits.engi.migration
  "Opt-in legacy-credit migration.

  A legacy balance is evidence for a Commons claim, never an EN opening mint.
  No claim changes balances until ordinary Commons governance approves a
  bounded issuance."
  (:require [credits.engi.codec :as codec]))

(defn export-claim
  [{:keys [subject legacy-balance evidence-cids consent? exported-at]}]
  (cond
    (not consent?)
    {:ok? false :error :participant-consent-required}

    (or (not (string? subject))
        (not (nat-int? legacy-balance))
        (empty? evidence-cids))
    {:ok? false :error :invalid-legacy-claim}

    :else
    {:ok? true
     :claim
     (codec/with-event-id
      {:type :legacy-opening-claim
       :subject subject
       :legacy-balance legacy-balance
       :evidence-cids (vec (sort evidence-cids))
       :exported-at exported-at
       :status :pending-commons-review
       :monetary-effect 0})}))

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
