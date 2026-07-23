(ns credits.engi.audit
  "Reproducible protocol audit bundle."
  (:require [clojure.string :as str]
            [credits.engi.codec :as codec]
            [credits.methods.engi :as engi]))

(defn audit-state [state]
  (let [events (:accepted-events state)
        nonce-slots (->> events
                         (filter #(= :transfer (:type %)))
                         (map (juxt :from :nonce)))
        unique-event-ids? (= (count events)
                             (count (set (map :id events))))
        unique-nonces? (= (count nonce-slots)
                          (count (set nonce-slots)))
        event-ids-valid? (every? codec/valid-event-id? events)]
    {:ok? (and (engi/valid-state? state)
               unique-event-ids?
               unique-nonces?
               event-ids-valid?)
     :mutual-credit-net (engi/mutual-credit-net state)
     :event-count (count events)
     :unique-event-ids? unique-event-ids?
     :unique-nonces? unique-nonces?
     :event-ids-valid? event-ids-valid?}))

(defn audit-legacy-manifest [manifest-text]
  (let [disabled? (boolean (re-find #"\"enabled\"\s*:\s*false"
                                    manifest-text))
        t3? (boolean (re-find #"\"legacyExecutionTier\"\s*:\s*\"T3\""
                              manifest-text))
        capability-section
        (or (second
             (re-find #"(?s)\"capabilities\"\s*:\s*\[(.*?)\]"
                      manifest-text))
            "")
        write-capability? (str/includes? capability-section "\"graph.write\"")]
    {:ok? (and disabled? t3? (not write-capability?))
     :disabled? disabled?
     :legacy-tier-t3? t3?
     :write-capability? write-capability?}))
