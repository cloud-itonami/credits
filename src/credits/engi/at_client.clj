(ns credits.engi.at-client
  "Minimal authenticated AT XRPC client for ENGI record replication.

  Tokens are supplied by the participant and are never persisted here. PDS
  responses remain untrusted until `atproto/record->event` succeeds."
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [credits.engi.atproto :as atproto])
  (:import (java.net URI URLEncoder)
           (java.net.http HttpClient HttpRequest
                          HttpRequest$BodyPublishers HttpResponse$BodyHandlers)
           (java.nio.charset StandardCharsets)
           (java.time Duration)))

(defn- encode [value]
  (URLEncoder/encode (str value) StandardCharsets/UTF_8))

(defn- query-string [parameters]
  (->> parameters
       (remove (comp nil? val))
       (sort-by (comp name key))
       (map (fn [[key value]]
              (str (encode (name key)) "=" (encode value))))
       (str/join "&")))

(defn- send!
  [{:keys [service access-token]} method nsid body query]
  (let [service-uri (try
                      (URI/create service)
                      (catch Exception _ nil))
        secure? (and service-uri
                     (or (= "https" (.getScheme service-uri))
                         (and (= "http" (.getScheme service-uri))
                              (#{"127.0.0.1" "localhost" "::1"}
                               (.getHost service-uri)))))]
    (when-not secure?
      (throw (ex-info "PDS must use HTTPS (HTTP is loopback-only)"
                      {:error :insecure-service})))
    (let [uri (str (str/replace service #"/$" "")
                 "/xrpc/" nsid
                 (when (seq query) (str "?" (query-string query))))
        builder (-> (HttpRequest/newBuilder (URI/create uri))
                    (.timeout (Duration/ofSeconds 15))
                    (.header "Accept" "application/json"))
        builder (cond-> builder
                  (string? access-token)
                  (.header "Authorization" (str "Bearer " access-token)))
        builder (if body
                  (-> builder
                      (.header "Content-Type" "application/json")
                      (.method method
                               (HttpRequest$BodyPublishers/ofString
                                (json/write-str body))))
                  (.method builder method
                           (HttpRequest$BodyPublishers/noBody)))
        response (.send (HttpClient/newHttpClient)
                        (.build builder)
                        (HttpResponse$BodyHandlers/ofString))
        parsed (try
                 (json/read-str (.body response) :key-fn keyword)
                 (catch Exception _ {:raw-body (.body response)}))]
      {:ok? (<= 200 (.statusCode response) 299)
       :status (.statusCode response)
       :body parsed})))

(defn create-record!
  [client repo event created-at]
  (send! client "POST" "com.atproto.repo.createRecord"
         (atproto/create-record-request repo event created-at)
         nil))

(defn list-records!
  ([client repo] (list-records! client repo nil))
  ([client repo cursor]
   (send! client "GET" "com.atproto.repo.listRecords" nil
          (atproto/list-records-query repo cursor))))

(defn verified-events!
  "Page once and independently validate every returned record."
  ([client repo] (verified-events! client repo nil))
  ([client repo cursor]
   (let [response (list-records! client repo cursor)]
     (if-not (:ok? response)
       response
       (let [decoded
             (mapv (fn [item]
                     (atproto/record->event (:value item)))
                   (get-in response [:body :records]))]
         (if-let [failure (first (remove :ok? decoded))]
           {:ok? false :error :untrusted-pds-record
            :record-error (:error failure)}
           {:ok? true
            :events (mapv :event decoded)
            :cursor (get-in response [:body :cursor])}))))))
