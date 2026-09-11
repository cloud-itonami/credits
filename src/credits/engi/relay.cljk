(ns credits.engi.relay
  "Replaceable content relay. It stores signed immutable events by content id
  and has no balance, ordering, minting, or conflict-resolution authority."
  (:require [credits.engi.codec :as codec]
            [credits.engi.journal :as journal]))

(defn empty-relay [relay-id]
  {:relay-id relay-id :events {}})

(defn publish [relay events]
  (reduce
   (fn [result event]
     (if-not (:ok? result)
       (reduced result)
       (cond
         (not (codec/valid-event-id? event))
         (reduced {:ok? false :error :event-id-mismatch
                   :event-id (:id event)})

         (contains? (:events result) (:id event))
         (let [merged
               (codec/merge-proof-enrichment
                (get-in result [:events (:id event)]) event)]
           (if (:ok? merged)
             (assoc-in result [:events (:id event)] (:event merged))
             (reduced (assoc merged :event-id (:id event)))))

         :else
         (assoc-in result [:events (:id event)] event))))
   (assoc relay :ok? true)
   events))

(defn fetch [relay wanted-ids]
  (if (seq wanted-ids)
    (vec (keep #(get-in relay [:events %]) wanted-ids))
    (->> (:events relay) (sort-by key) (mapv val))))

(defn fetch-page
  "Content-id pagination is transport enumeration only, never event order."
  [relay cursor limit]
  (let [ids (->> (keys (:events relay))
                 sort
                 (drop-while #(and cursor (<= (compare % cursor) 0))))
        selected (vec (take limit ids))
        more? (seq (drop limit ids))]
    {:events (mapv #(get-in relay [:events %]) selected)
     :cursor (when more? (last selected))}))

(defn union-from-relays [relays]
  (journal/merge-journals
   (mapv (fn [relay]
           {:owner-did (str "relay:" (:relay-id relay))
            :device-id (:relay-id relay)
            :events (:events relay)})
         relays)))
