(ns credits.engi-r7-adapters-test
  (:require [clojure.test :refer [deftest is testing]]
            [credits.engi.atproto :as atproto]
            [credits.engi.codec :as codec]
            [credits.engi.journal :as journal]
            [credits.engi.transport :as transport]))

(def event-a
  (codec/with-event-id
   {:type :test-evidence :value 1 :parents []}))

(def event-b
  (codec/with-event-id
   {:type :test-evidence :value 2 :parents [(:id event-a)]}))

(deftest at-record-is-a-verifiable-copy-not-authority
  (let [request (atproto/create-record-request
                 "did:plc:alice" event-a "2026-07-23T12:00:00Z")
        record (:record request)]
    (is (= atproto/collection (:collection request)))
    (is (= (subs (:id event-a) 4) (:rkey request)))
    (is (= {:ok? true :event event-a}
           (atproto/record->event record)))
    (testing "PDS metadata cannot replace or mutate signed content"
      (is (= :record-event-id-mismatch
             (:error (atproto/record->event
                      (assoc record :eventId "en1:forged")))))
      (is (= :non-canonical-event
             (:error (atproto/record->event
                      (update record :canonicalEvent #(str " " %)))))))))

(deftest two-real-http-relays-converge-without-order-authority
  (let [r1 (transport/start-relay! {:relay-id "relay-a"})
        r2 (transport/start-relay! {:relay-id "relay-b"})
        u1 (str "http://" (:host r1) ":" (:port r1))
        u2 (str "http://" (:host r2) ":" (:port r2))]
    (try
      (is (= 200 (:status (transport/publish! u1 [event-a]))))
      (is (= 200 (:status (transport/publish! u2 [event-b]))))
      (let [gossip (transport/gossip-once! [u2 u1
                                             "http://127.0.0.1:1"])]
        (is (:ok? gossip))
        (is (= 2 (:event-count gossip)))
        (is (= ["http://127.0.0.1:1"] (:unreachable gossip))))
      (let [events-1 (get-in (transport/fetch! u1) [:body :events])
            events-2 (get-in (transport/fetch! u2) [:body :events])
            merged (journal/merge-journals
                    [{:events (into {} (map (juxt :id identity)) events-1)}
                     {:events (into {} (map (juxt :id identity)) events-2)}])]
        (is (:ok? merged))
        (is (= [(:id event-a) (:id event-b)]
               (mapv :id (:events merged)))))
      (testing "a selected immutable object can be fetched"
        (is (= [event-b]
               (get-in (transport/fetch! u1 [(:id event-b)])
                       [:body :events]))))
      (testing "forged content is rejected at the network edge"
        (let [forged (assoc event-a :value 99)
              response (transport/publish! u1 [forged])]
          (is (= 422 (:status response)))
          (is (= :event-id-mismatch (get-in response [:body :error])))))
      (finally
        ((:stop! r1))
        ((:stop! r2))))))

(deftest durable-relay-recovers-after-process-restart
  (let [file (java.io.File/createTempFile "engi-relay-" ".edn")
        path (.getAbsolutePath file)]
    (.delete file)
    (try
      (let [first-run (transport/start-relay!
                       {:relay-id "durable" :journal-path path})
            url (str "http://" (:host first-run) ":" (:port first-run))]
        (is (= 200 (:status (transport/publish! url [event-a event-b]))))
        ((:stop! first-run)))
      (let [second-run (transport/start-relay!
                        {:relay-id "durable" :journal-path path})
            url (str "http://" (:host second-run) ":" (:port second-run))]
        (try
          (is (= #{(:id event-a) (:id event-b)}
                 (set (map :id (get-in (transport/fetch! url)
                                      [:body :events])))))
          (finally
            ((:stop! second-run)))))
      (finally
        (.delete (java.io.File. path))))))
