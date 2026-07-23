(ns credits.engi.transport
  "HTTP transport for replaceable ENGI content relays.

  The wire service exposes immutable signed events only. It has no endpoint for
  balances, minting, ordering, checkpoints, or conflict resolution."
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [credits.engi.codec :as codec]
            [credits.engi.relay :as relay]
            [credits.engi.store :as store])
  (:import (com.sun.net.httpserver HttpExchange HttpHandler HttpServer)
           (java.net InetSocketAddress URI)
           (java.net.http HttpClient HttpRequest
                          HttpRequest$BodyPublishers HttpResponse$BodyHandlers)
           (java.nio.charset StandardCharsets)
           (java.time Duration)))

(def max-request-bytes (* 1024 1024))

(defn- response! [^HttpExchange exchange status body]
  (let [bytes (.getBytes (codec/canonical-string body) StandardCharsets/UTF_8)]
    (.set (.getResponseHeaders exchange) "Content-Type"
          "application/edn; charset=utf-8")
    (.sendResponseHeaders exchange status (alength bytes))
    (with-open [out (.getResponseBody exchange)]
      (.write out bytes))))

(defn- read-limited [^HttpExchange exchange]
  (with-open [in (.getRequestBody exchange)]
    (let [bytes (.readNBytes in (inc max-request-bytes))]
      (when (<= (alength bytes) max-request-bytes)
        (String. bytes StandardCharsets/UTF_8)))))

(defn- query-ids [^URI uri]
  (when-let [query (.getRawQuery uri)]
    (->> (str/split query #"&")
         (keep (fn [part]
                 (when (str/starts-with? part "id=")
                   (java.net.URLDecoder/decode
                    (subs part 3) StandardCharsets/UTF_8))))
         vec)))

(defn handler
  "Create a relay handler backed by an atom. The atom is a replaceable cache,
  not authoritative state."
  ([relay-state] (handler relay-state nil))
  ([relay-state journal-path]
  (reify HttpHandler
    (handle [_ exchange]
      (try
        (let [method (.getRequestMethod exchange)
              path (.getPath (.getRequestURI exchange))]
          (cond
            (and (= "POST" method) (= "/v1/events" path))
            (if-let [body (read-limited exchange)]
              (let [request (edn/read-string body)
                    events (:events request)]
                (if-not (vector? events)
                  (response! exchange 400 {:ok? false :error :invalid-request})
                  (let [validation (relay/publish @relay-state events)
                        persisted
                        (when (and (:ok? validation) journal-path)
                          (reduce
                           (fn [result event]
                             (if-not (:ok? result)
                               (reduced result)
                               (store/append-event! journal-path event)))
                           {:ok? true}
                           events))]
                    (if (:ok? validation)
                      (if (or (nil? journal-path) (:ok? persisted))
                        (do
                          (reset! relay-state (dissoc validation :ok?))
                          (response! exchange 200
                                     {:ok? true
                                      :accepted-event-ids (mapv :id events)}))
                        (response! exchange 503
                                   {:ok? false :error :persistence-failed}))
                      (response! exchange 422
                                 (select-keys validation
                                              [:ok? :error :event-id]))))))
              (response! exchange 413 {:ok? false :error :request-too-large}))

            (and (= "GET" method) (= "/v1/events" path))
            (response! exchange 200
                       {:ok? true
                        :events (relay/fetch @relay-state
                                             (query-ids
                                              (.getRequestURI exchange)))})

            :else
            (response! exchange 404 {:ok? false :error :not-found})))
        (catch Exception _
          (response! exchange 400 {:ok? false :error :invalid-edn}))
        (finally
          (.close exchange)))))))

(defn start-relay!
  "Start a loopback or explicitly bound relay. Returns a stop function and the
  actual port. Callers choose independent persistence/hosting."
  [{:keys [host port relay-id journal-path]
    :or {host "127.0.0.1" port 0}}]
  (let [loaded (if journal-path
                 (store/load-events journal-path)
                 {:ok? true :events []})]
    (when-not (:ok? loaded)
      (throw (ex-info "Relay journal failed verification" loaded)))
    (let [seeded (relay/publish (relay/empty-relay relay-id)
                                (:events loaded))
          _ (when-not (:ok? seeded)
              (throw (ex-info "Relay journal contains invalid content" seeded)))
          state (atom (dissoc seeded :ok?))
        server (HttpServer/create (InetSocketAddress. host (int port)) 0)]
      (.createContext server "/" (handler state journal-path))
      (.setExecutor server nil)
      (.start server)
      {:host host
       :port (.getPort (.getAddress server))
       :state state
       :journal-path journal-path
       :stop! #(.stop server 0)})))

(defn- request [method uri body]
  (let [builder (-> (HttpRequest/newBuilder (URI/create uri))
                    (.timeout (Duration/ofSeconds 10))
                    (.header "Accept" "application/edn"))
        builder (if body
                  (-> builder
                      (.header "Content-Type" "application/edn")
                      (.method method
                               (HttpRequest$BodyPublishers/ofString
                                (codec/canonical-string body))))
                  (.method builder method
                           (HttpRequest$BodyPublishers/noBody)))
        response (.send (HttpClient/newHttpClient)
                        (.build builder)
                        (HttpResponse$BodyHandlers/ofString))]
    {:status (.statusCode response)
     :body (edn/read-string (.body response))}))

(defn publish!
  [base-url events]
  (request "POST" (str base-url "/v1/events") {:events (vec events)}))

(defn fetch!
  ([base-url] (fetch! base-url []))
  ([base-url ids]
   (let [query (when (seq ids)
                 (str "?"
                      (str/join
                       "&"
                       (map #(str "id="
                                  (java.net.URLEncoder/encode
                                   % StandardCharsets/UTF_8))
                            ids))))]
     (request "GET" (str base-url "/v1/events" query) nil))))

(defn gossip-once!
  "Union immutable objects across reachable relays, then republish the union.
  This is safe to run by any participant: it cannot select event order or
  suppress a valid fork, and a failed relay does not change the other copies."
  [base-urls]
  (let [fetched
        (mapv (fn [url]
                (try
                  {:url url :response (fetch! url)}
                  (catch Exception e
                    {:url url :error (.getMessage e)})))
              base-urls)
        reachable (filter #(= 200 (get-in % [:response :status])) fetched)
        by-id
        (reduce
         (fn [events item]
           (reduce (fn [acc event]
                     (if-let [existing (get acc (:id event))]
                       (if (= (codec/canonical-string existing)
                              (codec/canonical-string event))
                         acc
                         (throw (ex-info "Relay returned an event-id collision"
                                         {:event-id (:id event)})))
                       (assoc acc (:id event) event)))
                   events
                   (get-in item [:response :body :events])))
         {}
         reachable)
        events (mapv val (sort-by key by-id))
        published
        (mapv (fn [{:keys [url]}]
                {:url url :response (publish! url events)})
              reachable)]
    {:ok? (and (seq reachable)
               (every? #(= 200 (get-in % [:response :status])) published))
     :reachable (mapv :url reachable)
     :unreachable (mapv :url (remove #(= 200
                                        (get-in % [:response :status]))
                                     fetched))
     :event-count (count events)
     :published published}))
