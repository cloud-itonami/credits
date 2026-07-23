(ns credits.engi.sync
  "Participant-side synchronization between a durable journal and replaceable
  relays. The participant performs validation and dependency merge locally."
  (:require [credits.engi.journal :as journal]
            [credits.engi.store :as store]
            [credits.engi.transport :as transport]))

(defn- safely [operation url]
  (try
    {:url url :response (operation url)}
    (catch Exception e
      {:url url :error (.getMessage e)})))

(defn sync-device!
  "Publish a device's retained events, gossip reachable relays, fetch their
  union, and append the deterministic dependency order locally. A relay outage
  is tolerated when at least one relay is reachable. Conflicts remain
  quarantined by `journal/merge-journals`."
  [journal-path relay-urls]
  (let [local (store/load-events journal-path)]
    (if-not (:ok? local)
      local
      (let [published (mapv #(safely
                              (fn [url]
                                (transport/publish! url (:events local)))
                              %)
                            relay-urls)
            gossip (transport/gossip-once! relay-urls)
            fetched (mapv #(safely transport/fetch! %) relay-urls)
            reachable (filter #(= 200 (get-in % [:response :status]))
                              fetched)]
        (if (empty? reachable)
          {:ok? false :error :no-reachable-relay
           :published published}
          (let [journals
                (into [{:owner-did "local"
                        :device-id journal-path
                        :events (into {} (map (juxt :id identity))
                                      (:events local))}]
                      (map (fn [item]
                             {:owner-did (str "relay:" (:url item))
                              :device-id (:url item)
                              :events
                              (into {} (map (juxt :id identity))
                                    (get-in item
                                            [:response :body :events]))})
                           reachable))
                merged (journal/merge-journals journals)]
            (if-not (:ok? merged)
              (assoc merged :published published :gossip gossip)
              (let [writes
                    (mapv #(store/append-event! journal-path %)
                          (:events merged))]
                (if-let [failed (first (remove :ok? writes))]
                  (assoc failed :error :local-journal-write-failed)
                  {:ok? true
                   :event-count (count (:events merged))
                   :reachable-relays (mapv :url reachable)
                   :unreachable-relays
                   (mapv :url (remove #(= 200
                                         (get-in % [:response :status]))
                                      fetched))
                   :gossip gossip})))))))))
