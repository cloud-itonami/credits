(ns credits.engi.atproto
  "AT Protocol record adapter for signed ENGI events.

  A PDS is a discovery and replication substrate only. Consumers recover the
  canonical event and independently verify its content id and signatures."
  (:require [clojure.edn :as edn]
            [kotoba.lang.text :as str]
            [credits.engi.codec :as codec]))

(def collection "com.etzhayyim.engi.event")

(defn event-rkey [event]
  (when-not (codec/valid-event-id? event)
    (throw (ex-info "Cannot publish an invalid ENGI event"
                    {:error :event-id-mismatch})))
  (subs (:id event) (count "en1:")))

(defn event-record
  "Encode a canonical event in an AT-compatible map. `createdAt` is metadata
  and deliberately excluded from ENGI economic state."
  [event created-at]
  {:$type collection
   :eventId (:id event)
   :canonicalEvent (codec/canonical-string event)
   :createdAt created-at})

(defn create-record-request
  [repo event created-at]
  {:repo repo
   :collection collection
   :rkey (event-rkey event)
   :validate true
   :record (event-record event created-at)})

(defn put-record-request
  [repo event created-at swap-record]
  (cond-> (create-record-request repo event created-at)
    (string? swap-record) (assoc :swapRecord swap-record)))

(defn record->event
  "Decode an AT record fail-closed. URI/CID metadata from a PDS is not trusted."
  [record]
  (try
    (cond
      (not= collection (:$type record))
      {:ok? false :error :wrong-collection}

      (not (string? (:canonicalEvent record)))
      {:ok? false :error :missing-canonical-event}

      :else
      (let [event (edn/read-string (:canonicalEvent record))]
        (cond
          (not= (:canonicalEvent record) (codec/canonical-string event))
          {:ok? false :error :non-canonical-event}

          (not (codec/valid-event-id? event))
          {:ok? false :error :event-id-mismatch}

          (not= (:eventId record) (:id event))
          {:ok? false :error :record-event-id-mismatch}

          :else {:ok? true :event event})))
    (catch Exception e
      {:ok? false :error :invalid-record :message (.getMessage e)})))

(defn list-records-query
  ([repo] (list-records-query repo nil))
  ([repo cursor]
   (cond-> {:repo repo :collection collection :limit 100}
     (and (string? cursor) (not (str/blank? cursor)))
     (assoc :cursor cursor))))

(defn get-record-query [repo event]
  {:repo repo
   :collection collection
   :rkey (event-rkey event)})
