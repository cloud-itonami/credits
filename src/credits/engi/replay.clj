(ns credits.engi.replay
  "Untrusted-journal replay. A snapshot is accepted only if every event id,
  signature, transition, and final invariant verifies."
  (:require [credits.engi.codec :as codec]
            [credits.engi.crypto :as crypto]
            [credits.methods.engi :as engi]))

(defn apply-event [state event resolve-public-key]
  (if-not (codec/valid-event-id? event)
    {:ok? false :error :event-id-mismatch}
    (let [verify? (partial crypto/verify-evidence? resolve-public-key)]
      (case (:type event)
        :credit-line
        (engi/establish-credit-line state event verify?)

        :transfer
        (engi/apply-transfer state event verify?)

        :commons-issuance
        (engi/apply-commons-issuance state event verify?)

        {:ok? false :error :unknown-event-type}))))

(defn replay
  ([events resolve-public-key]
   (replay engi/zero-state events resolve-public-key))
  ([initial-state events resolve-public-key]
   (loop [state initial-state
          index 0
          remaining events]
     (if-let [event (first remaining)]
       (let [result (apply-event state event resolve-public-key)]
         (if (:ok? result)
           (recur (:state result) (inc index) (next remaining))
           (assoc result :index index :event-id (:id event))))
       (if (engi/valid-state? state)
         {:ok? true
          :state state
          :state-root
          (codec/event-id
           {:type :checkpoint
            :event-ids (mapv :id (:accepted-events state))
            :balances (:balances state)
            :credit-lines (:credit-lines state)
            :commons-issued-by-epoch (:commons-issued-by-epoch state)})}
         {:ok? false :error :invalid-final-state :index index})))))
