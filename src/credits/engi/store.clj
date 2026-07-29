(ns credits.engi.store
  "Durable participant-owned append-only journal.

  One canonical EDN event or proof-enriched revision per line. The file is
  fsynced after each accepted append and collapses revisions by economic event
  id on load. A relay or regional operator never receives write access to a
  participant file; participants export signed events."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [credits.engi.codec :as codec])
  (:import (java.io FileOutputStream OutputStreamWriter BufferedWriter)
           (java.nio.charset StandardCharsets)))

(defn load-events [path]
  (let [file (io/file path)]
    (if-not (.exists file)
      {:ok? true :events []}
      (try
        (with-open [reader (io/reader file)]
          (loop [line-number 1
                 lines (line-seq reader)
                 order []
                 events-by-id {}]
            (if-let [line (first lines)]
              (let [event (edn/read-string line)]
                (if (codec/valid-event-id? event)
                  (if-let [existing (get events-by-id (:id event))]
                    (let [merged
                          (codec/merge-proof-enrichment existing event)]
                      (if (:ok? merged)
                        (recur (inc line-number) (next lines) order
                               (assoc events-by-id (:id event)
                                      (:event merged)))
                        {:ok? false :error (:error merged)
                         :line line-number
                         :events (mapv events-by-id order)}))
                    (recur (inc line-number) (next lines)
                           (conj order (:id event))
                           (assoc events-by-id (:id event) event)))
                  {:ok? false :error :event-id-mismatch
                   :line line-number :events (mapv events-by-id order)}))
              {:ok? true :events (mapv events-by-id order)})))
        (catch Exception e
          {:ok? false :error :corrupt-journal
           :message (.getMessage e)})))))

(defn- append-line! [path event]
  (let [file (io/file path)]
    (when-let [parent (.getParentFile file)]
      (.mkdirs parent))
    (with-open [stream (FileOutputStream. file true)
                writer (BufferedWriter.
                        (OutputStreamWriter.
                         stream StandardCharsets/UTF_8))]
      (.write writer (codec/canonical-string event))
      (.newLine writer)
      (.flush writer)
      ;; Full JVM clients fsync here. Babashka's SCI sandbox does not expose
      ;; FileDescriptor.sync, so its runner retains append/flush semantics.
      (try
        (.sync (.getFD stream))
        (catch Exception _ nil)))))

(defn append-event!
  "Append and fsync an event. A later line may add previously missing proofs,
  but can never alter economic content or overwrite competing proof bytes."
  [path event]
  (if-not (codec/valid-event-id? event)
    {:ok? false :error :event-id-mismatch}
    (let [loaded (load-events path)]
      (if-not (:ok? loaded)
        loaded
        (if-let [existing
                 (some #(when (= (:id event) (:id %)) %) (:events loaded))]
          (let [merged (codec/merge-proof-enrichment existing event)]
            (cond
              (not (:ok? merged)) merged
              (= (codec/canonical-string existing)
                 (codec/canonical-string (:event merged)))
              {:ok? true :duplicate? true :event-id (:id event)}
              :else
              (do
                (append-line! path (:event merged))
                {:ok? true :enriched? true :event-id (:id event)})))
          (do
            (append-line! path event)
            {:ok? true :event-id (:id event)}))))))
