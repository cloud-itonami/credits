(ns credits.engi.journal
  "Participant-owned offline journals and deterministic three-way merge.

  Relays transport journals but have no ordering authority. Dependencies are
  explicit parent event ids. Concurrent spends of the same participant nonce
  are surfaced as a conflict and never resolved by arrival time."
  (:require [credits.engi.codec :as codec]
            [credits.engi.replay :as replay]))

(defn new-journal [owner-did device-id]
  {:owner-did owner-did
   :device-id device-id
   :events {}})

(defn append-event [journal event]
  (cond
    (not (codec/valid-event-id? event))
    {:ok? false :error :event-id-mismatch}

    (contains? (:events journal) (:id event))
    (let [merged (codec/merge-proof-enrichment
                  (get-in journal [:events (:id event)]) event)]
      (if (:ok? merged)
        {:ok? true
         :journal (assoc-in journal [:events (:id event)] (:event merged))
         :duplicate? (= (codec/canonical-string (:event merged))
                        (codec/canonical-string
                         (get-in journal [:events (:id event)])))}
        merged))

    :else
    {:ok? true
     :journal (assoc-in journal [:events (:id event)] event)}))

(defn- merge-event-maps [journals]
  (reduce
   (fn [result [id event]]
     (if-not (:ok? result)
       (reduced result)
       (cond
         (not (codec/valid-event-id? event))
         (reduced {:ok? false :error :event-id-mismatch :event-id id})

         (get-in result [:events id])
         (let [existing (get-in result [:events id])
               merged (codec/merge-proof-enrichment existing event)]
           (if (:ok? merged)
             (assoc-in result [:events id] (:event merged))
             (reduced (assoc merged :event-id id))))

         :else
         (assoc-in result [:events id] event))))
   {:ok? true :events {}}
   (mapcat (comp seq :events) journals)))

(defn- nonce-conflicts [events]
  (->> events
       (filter #(= :transfer (:type %)))
       (group-by (juxt :from :nonce))
       (keep (fn [[slot candidates]]
               (when (< 1 (count (set (map :id candidates))))
                 {:slot slot
                  :event-ids (vec (sort (map :id candidates)))})))
       vec))

(defn- topological-order [events]
  (let [by-id (into {} (map (juxt :id identity)) events)
        known (set (keys by-id))
        missing (->> events
                     (mapcat :parents)
                     (remove known)
                     set)]
    (if (seq missing)
      {:ok? false :error :missing-parent :parent-ids (vec (sort missing))}
      (loop [ordered []
             emitted #{}
             remaining by-id]
        (if (empty? remaining)
          {:ok? true :events ordered}
          (let [ready (->> remaining
                           vals
                           (filter #(every? emitted (or (:parents %) [])))
                           (sort-by :id)
                           vec)]
            (if (empty? ready)
              {:ok? false :error :dependency-cycle
               :event-ids (vec (sort (keys remaining)))}
              (recur (into ordered ready)
                     (into emitted (map :id ready))
                     (apply dissoc remaining (map :id ready))))))))))

(defn merge-journals [journals]
  (let [merged (merge-event-maps journals)]
    (if-not (:ok? merged)
      merged
      (let [events (vals (:events merged))
            conflicts (nonce-conflicts events)]
        (if (seq conflicts)
          {:ok? false :error :concurrent-spend-conflict
           :conflicts conflicts :events (:events merged)}
          (topological-order events))))))

(defn merge-and-replay [journals resolve-public-key]
  (let [merged (merge-journals journals)]
    (if (:ok? merged)
      (assoc (replay/replay (:events merged) resolve-public-key)
             :merged-event-count (count (:events merged)))
      merged)))
