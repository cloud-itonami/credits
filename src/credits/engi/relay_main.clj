(ns credits.engi.relay-main
  "Executable entry point for an independently operated ENGI relay."
  (:require [clojure.string :as str]
            [credits.engi.transport :as transport])
  (:gen-class))

(defn config-from-env [environment]
  (let [relay-id (get environment "ENGI_RELAY_ID")
        host (get environment "ENGI_RELAY_HOST" "127.0.0.1")
        port-text (get environment "ENGI_RELAY_PORT" "8080")
        journal-path (get environment "ENGI_RELAY_JOURNAL")]
    (when (str/blank? relay-id)
      (throw (ex-info "ENGI_RELAY_ID is required"
                      {:error :relay-id-required})))
    (when (str/blank? journal-path)
      (throw (ex-info "ENGI_RELAY_JOURNAL is required"
                      {:error :journal-required})))
    (let [port (try
                 (Integer/parseInt port-text)
                 (catch Exception _
                   (throw (ex-info "ENGI_RELAY_PORT must be an integer"
                                   {:error :invalid-port}))))]
      (when-not (<= 1 port 65535)
        (throw (ex-info "ENGI_RELAY_PORT is outside 1..65535"
                        {:error :invalid-port})))
      {:relay-id relay-id
       :host host
       :port port
       :journal-path journal-path})))

(defn -main [& _]
  (let [relay (transport/start-relay! (config-from-env (System/getenv)))
        stopped (promise)
        stop! (fn []
                ((:stop! relay))
                (deliver stopped true))]
    (.addShutdownHook
     (Runtime/getRuntime)
     (Thread. ^Runnable stop! "engi-relay-shutdown"))
    (println (str "ENGI relay " (:relay-id @(:state relay))
                  " listening on " (:host relay) ":" (:port relay)))
    (flush)
    @stopped))
