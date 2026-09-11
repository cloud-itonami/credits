(ns credits.engi-sync-test
  (:require [clojure.test :refer [deftest is]]
            [credits.engi.codec :as codec]
            [credits.engi.store :as store]
            [credits.engi.sync :as sync]
            [credits.engi.transport :as transport]))

(def sync-root
  (codec/with-event-id {:type :sync-root :parents [] :device "a"}))

(def sync-child
  (codec/with-event-id
   {:type :sync-child :parents [(:id sync-root)] :device "b"}))

(defn temp-journal []
  (let [file (java.io.File/createTempFile "engi-device-" ".edn")
        path (.getAbsolutePath file)]
    (.delete file)
    path))

(deftest three-durable-devices-sync-across-replaceable-relays
  (let [paths (repeatedly 3 temp-journal)
        relay-a (transport/start-relay! {:relay-id "a"})
        relay-b (transport/start-relay! {:relay-id "b"})
        url-a (str "http://" (:host relay-a) ":" (:port relay-a))
        url-b (str "http://" (:host relay-b) ":" (:port relay-b))
        dead "http://127.0.0.1:1"]
    (try
      (is (:ok? (store/append-event! (first paths) sync-root)))
      (is (:ok? (sync/sync-device! (first paths) [url-a url-b dead])))
      (is (:ok? (sync/sync-device! (second paths) [dead url-b])))
      (is (= [sync-root] (:events (store/load-events (second paths)))))

      (is (:ok? (store/append-event! (second paths) sync-child)))
      ((:stop! relay-a))
      (is (:ok? (sync/sync-device! (second paths) [url-a url-b])))
      (is (:ok? (sync/sync-device! (nth paths 2) [url-a url-b])))
      (is (= [(:id sync-root) (:id sync-child)]
             (mapv :id (:events (store/load-events (nth paths 2))))))

      (let [replacement-a (transport/start-relay! {:relay-id "a2"})]
        (try
          (let [replacement-url
                (str "http://" (:host replacement-a) ":"
                     (:port replacement-a))]
            (is (:ok? (sync/sync-device! (nth paths 2)
                                         [replacement-url url-b])))
            (is (= #{(:id sync-root) (:id sync-child)}
                   (set (map :id
                             (get-in (transport/fetch! replacement-url)
                                     [:body :events]))))))
          (finally
            ((:stop! replacement-a)))))
      (finally
        ((:stop! relay-b))
        (doseq [path paths]
          (.delete (java.io.File. path)))))))

(deftest device-does-not-pretend-to-sync-with-no-reachable-copy
  (let [path (temp-journal)]
    (try
      (is (= :no-reachable-relay
             (:error (sync/sync-device!
                      path ["http://127.0.0.1:1"]))))
      (finally
        (.delete (java.io.File. path))))))
