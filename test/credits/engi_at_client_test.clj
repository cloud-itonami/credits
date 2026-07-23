(ns credits.engi-at-client-test
  (:require [clojure.data.json :as json]
            [clojure.test :refer [deftest is testing]]
            [credits.engi.at-client :as client]
            [credits.engi.atproto :as atproto]
            [credits.engi.codec :as codec])
  (:import (com.sun.net.httpserver HttpHandler HttpServer)
           (java.net InetSocketAddress)
           (java.nio.charset StandardCharsets)))

(def pds-event
  (codec/with-event-id {:type :pds-roundtrip :parents [] :value 7}))

(defn response! [exchange status body]
  (let [bytes (.getBytes (json/write-str body) StandardCharsets/UTF_8)]
    (.set (.getResponseHeaders exchange) "Content-Type" "application/json")
    (.sendResponseHeaders exchange status (alength bytes))
    (with-open [out (.getResponseBody exchange)]
      (.write out bytes))))

(defn mock-pds []
  (let [records (atom [])
        revision (atom 0)
        authorized? (fn [exchange]
                      (= "Bearer participant-token"
                         (.getFirst (.getRequestHeaders exchange)
                                    "Authorization")))
        server (HttpServer/create (InetSocketAddress. "127.0.0.1" 0) 0)]
    (.createContext
     server "/xrpc/com.atproto.repo.createRecord"
     (reify HttpHandler
       (handle [_ exchange]
         (try
           (if-not (authorized? exchange)
             (response! exchange 401 {:error "AuthRequired"})
             (let [body (json/read-str
                         (slurp (.getRequestBody exchange))
                         :key-fn keyword)]
               (swap! records conj
                      {:uri (str "at://" (:repo body) "/"
                                 (:collection body) "/" (:rkey body))
                       :cid (str "bafy-" (swap! revision inc))
                       :value (:record body)})
               (response! exchange 200
                          {:uri (:uri (last @records))
                           :cid (:cid (last @records))})))
           (finally (.close exchange))))))
    (.createContext
     server "/xrpc/com.atproto.repo.getRecord"
     (reify HttpHandler
       (handle [_ exchange]
         (try
           (cond
             (not (authorized? exchange))
             (response! exchange 401 {:error "AuthRequired"})
             (empty? @records)
             (response! exchange 404 {:error "RecordNotFound"})
             :else
             (response! exchange 200 (first @records)))
           (finally (.close exchange))))))
    (.createContext
     server "/xrpc/com.atproto.repo.putRecord"
     (reify HttpHandler
       (handle [_ exchange]
         (try
           (if-not (authorized? exchange)
             (response! exchange 401 {:error "AuthRequired"})
             (let [body (json/read-str
                         (slurp (.getRequestBody exchange))
                         :key-fn keyword)
                   current (first @records)]
               (if (not= (:swapRecord body) (:cid current))
                 (response! exchange 409 {:error "InvalidSwap"})
                 (let [record {:uri (:uri current)
                               :cid (str "bafy-" (swap! revision inc))
                               :value (:record body)}]
                   (reset! records [record])
                   (response! exchange 200
                              (select-keys record [:uri :cid]))))))
           (finally (.close exchange))))))
    (.createContext
     server "/xrpc/com.atproto.repo.listRecords"
     (reify HttpHandler
       (handle [_ exchange]
         (try
           (if-not (authorized? exchange)
             (response! exchange 401 {:error "AuthRequired"})
             (if (re-find #"(?:^|&)cursor=next-page(?:&|$)"
                          (or (.getRawQuery (.getRequestURI exchange)) ""))
               (response! exchange 200 {:records []})
               (response! exchange 200
                          {:records @records :cursor "next-page"})))
           (finally (.close exchange))))))
    (.start server)
    {:service (str "http://127.0.0.1:" (.getPort (.getAddress server)))
     :records records
     :stop! #(.stop server 0)}))

(deftest authenticated-pds-create-list-roundtrip-reverifies-content
  (let [pds (mock-pds)
        participant {:service (:service pds)
                     :access-token "participant-token"}]
    (try
      (let [created (client/create-record!
                     participant "did:plc:alice" pds-event
                     "2026-07-23T12:00:00Z")
            listed (client/verified-events! participant "did:plc:alice")]
        (is (:ok? created))
        (is (:ok? listed))
        (is (= [pds-event] (:events listed)))
        (is (= "next-page" (:cursor listed)))
        (is (= [pds-event]
               (:events
                (client/verified-all-events!
                 participant "did:plc:alice")))))
      (testing "the participant token is required"
        (is (= 401 (:status
                    (client/list-records!
                     {:service (:service pds)}
                     "did:plc:alice")))))
      (testing "a PDS cannot mutate canonical economic content"
        (swap! (:records pds) assoc-in [0 :value :eventId] "en1:forged")
        (let [result (client/verified-events! participant "did:plc:alice")]
          (is (not (:ok? result)))
          (is (= :untrusted-pds-record (:error result)))))
      (finally
        ((:stop! pds))))))

(deftest lexicon-identifies-the-canonical-record
  (let [lexicon (json/read-str
                 (slurp "lexicons/com/etzhayyim/engi/event.json"))]
    (is (= atproto/collection (get lexicon "id")))
    (is (= "record" (get-in lexicon ["defs" "main" "type"])))))

(deftest participant-token-is-never-sent-over-remote-plaintext-http
  (is (= :insecure-service
         (:error
          (try
            (client/list-records!
             {:service "http://pds.example"
              :access-token "must-not-leak"}
             "did:plc:alice")
            (catch clojure.lang.ExceptionInfo e
              (ex-data e)))))))

(deftest pds-proof-enrichment-uses-verified-cid-compare-and-swap
  (let [pds (mock-pds)
        participant {:service (:service pds)
                     :access-token "participant-token"}
        statement {:signer "did:plc:alice" :role :party}
        partial (codec/with-event-id
                 {:type :async-pds-proof :evidence [statement]})
        complete (assoc-in partial [:evidence 0]
                           (assoc statement
                                  :event-id (:id partial)
                                  :signature "candidate-proof"))
        created-at "2026-07-23T12:00:00Z"]
    (try
      (is (:ok? (client/upsert-event!
                 participant "did:plc:alice" partial created-at)))
      (is (:ok? (client/upsert-event!
                 participant "did:plc:alice" complete created-at)))
      (let [listed (client/verified-events! participant "did:plc:alice")]
        (is (:ok? listed))
        (is (= "candidate-proof"
               (get-in listed [:events 0 :evidence 0 :signature]))))
      (finally
        ((:stop! pds))))))
