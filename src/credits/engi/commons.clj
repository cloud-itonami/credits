(ns credits.engi.commons
  "Commons proposal, challenge, jury, and appeal state machine."
  (:require [clojure.set :as set]
            [credits.engi.codec :as codec]
            [credits.engi.crypto :as crypto]))

(def jury-roles #{:local :technical :commons-guardian :external})

(defn propose
  [{:keys [recipient amount epoch epoch-cap purpose evidence-cids
           challenge-closes-at]}]
  (let [proposal
        {:type :commons-proposal
         :recipient recipient
         :amount amount
         :epoch epoch
         :epoch-cap epoch-cap
         :purpose purpose
         :evidence-cids (vec (sort evidence-cids))
         :challenge-closes-at challenge-closes-at}]
    {:proposal (codec/with-event-id proposal)
     :status :open
     :challenges []
     :decisions []}))

(defn challenge [case challenge]
  (if (#{:open :challenged} (:status case))
    {:ok? true
     :case (-> case
               (update :challenges conj challenge)
               (assoc :status :challenged))}
    {:ok? false :error :case-not-challengeable}))

(defn- valid-votes [decision parties resolve-public-key]
  (->> (:votes decision)
       (filter (fn [vote]
                 (and (not (contains? parties (:signer vote)))
                      (crypto/verify-evidence?
                       resolve-public-key decision vote))))
       (reduce (fn [m vote] (assoc m (:signer vote) vote)) {})
       vals))

(defn decide
  "Initial jury is 5/7 minimum and must span every jury role. Appeal is 6/9
  minimum, uses a disjoint jury, and supersedes rather than deletes."
  [case decision resolve-public-key]
  (let [appeal? (= :appeal (:stage decision))
        previous-jurors
        (set (mapcat #(map :signer (:votes %)) (:decisions case)))
        parties #{(get-in case [:proposal :recipient])
                  (:challenger decision)}
        votes (vec (valid-votes decision parties resolve-public-key))
        jurors (set (map :signer votes))
        roles (set (map :role votes))
        required (if appeal? 6 5)
        expected-size (if appeal? 9 7)]
    (cond
      (not (codec/valid-event-id? decision))
      {:ok? false :error :event-id-mismatch}

      (not= expected-size (count (:votes decision)))
      {:ok? false :error :wrong-jury-size}

      (and appeal? (seq (set/intersection previous-jurors jurors)))
      {:ok? false :error :appeal-jury-not-independent}

      (< (count jurors) required)
      {:ok? false :error :jury-quorum-required}

      (not (every? roles jury-roles))
      {:ok? false :error :jury-role-quorum-required}

      :else
      (let [approve (count (filter #(= :approve (:choice %)) votes))
            reject (count (filter #(= :reject (:choice %)) votes))
            outcome (if (>= approve required) :approved
                        (if (>= reject required) :rejected :no-decision))]
        (if (= :no-decision outcome)
          {:ok? false :error :decision-threshold-not-met}
          {:ok? true
           :case (-> case
                     (update :decisions conj decision)
                     (assoc :status outcome))})))))

(defn authorized-issuance [case attestations]
  (when (= :approved (:status case))
    (codec/with-event-id
     {:type :commons-issuance
      :proposal-id (get-in case [:proposal :id])
      :recipient (get-in case [:proposal :recipient])
      :amount (get-in case [:proposal :amount])
      :epoch (get-in case [:proposal :epoch])
      :epoch-cap (get-in case [:proposal :epoch-cap])
      :attestations attestations})))
