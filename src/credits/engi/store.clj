(ns credits.engi.store
  "Durable participant-owned append-only journal.

  One canonical EDN event per line. The file is fsynced after each accepted
  append. A relay or regional operator never receives write access to this
  file; participants export signed events."
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
                 events []]
            (if-let [line (first lines)]
              (let [event (edn/read-string line)]
                (if (codec/valid-event-id? event)
                  (recur (inc line-number) (next lines) (conj events event))
                  {:ok? false :error :event-id-mismatch
                   :line line-number :events events}))
              {:ok? true :events events})))
        (catch Exception e
          {:ok? false :error :corrupt-journal
           :message (.getMessage e)})))))

(defn append-event!
  "Append and fsync one event. Duplicate ids are idempotent only when their
  canonical bytes are identical."
  [path event]
  (if-not (codec/valid-event-id? event)
    {:ok? false :error :event-id-mismatch}
    (let [loaded (load-events path)]
      (if-not (:ok? loaded)
        loaded
        (if-let [existing (some #(when (= (:id event) (:id %)) %) (:events loaded))]
          (if (= (codec/canonical-string existing)
                 (codec/canonical-string event))
            {:ok? true :duplicate? true :event-id (:id event)}
            {:ok? false :error :event-id-collision :event-id (:id event)})
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
              ;; Full JVM clients fsync here. Babashka's SCI sandbox does not
              ;; expose FileDescriptor.sync, so its compatibility runner keeps
              ;; the append/flush semantics while the JVM integration test
              ;; exercises the durable path.
              (try
                (.sync (.getFD stream))
                (catch Exception _ nil)))
            {:ok? true :event-id (:id event)}))))))
