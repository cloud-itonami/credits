(require 'clojure.test)
(load-file "src/credits/methods/engi.cljc")
(load-file "methods/test_engi.cljc")
(load-file "src/credits/engi/codec.clj")
(load-file "src/credits/engi/crypto.clj")
(load-file "src/credits/engi/replay.clj")
(load-file "src/credits/engi/journal.clj")
(load-file "src/credits/engi/identity.clj")
(load-file "src/credits/engi/store.clj")
(load-file "src/credits/engi/relay.clj")
(load-file "src/credits/engi/checkpoint.clj")
(load-file "src/credits/engi/standing.clj")
(load-file "src/credits/engi/commons.clj")
(load-file "src/credits/engi/netting.clj")
(load-file "src/credits/engi/migration.clj")
(load-file "src/credits/engi/audit.clj")
(load-file "src/credits/engi/benchmark.clj")
(load-file "src/credits/engi/atproto.clj")
(load-file "test/credits/engi_r1_test.clj")
(load-file "test/credits/engi_r2_test.clj")
(load-file "test/credits/engi_r3_test.clj")
(load-file "test/credits/engi_r4_test.clj")
(load-file "test/credits/engi_r5_test.clj")
(load-file "test/credits/engi_r6_test.clj")
(when-not (System/getProperty "babashka.version")
  ;; The production transport uses the JDK HttpServer/HttpClient modules,
  ;; and the WebAuthn verifier uses json.compat. JVM tests exercise both.
  (load-file "src/credits/engi/transport.clj")
  (load-file "src/credits/engi/relay_main.clj")
  (load-file "src/credits/engi/sync.clj")
  (load-file "src/credits/engi/webauthn.clj")
  (load-file "src/credits/engi/at_client.clj")
  (load-file "test/credits/engi_r7_adapters_test.clj")
  (load-file "test/credits/engi_webauthn_test.clj")
  (load-file "test/credits/engi_at_client_test.clj")
  (load-file "test/credits/engi_sync_test.clj"))
(let [result (clojure.test/run-tests 'credits.methods.test-engi)]
  (let [r1-result (clojure.test/run-tests 'credits.engi-r1-test)]
    (let [r2-result (clojure.test/run-tests 'credits.engi-r2-test)]
      (let [r3-result (clojure.test/run-tests 'credits.engi-r3-test)]
        (let [r4-result (clojure.test/run-tests 'credits.engi-r4-test)]
          (let [r5-result (clojure.test/run-tests 'credits.engi-r5-test)]
            (let [r6-result (clojure.test/run-tests 'credits.engi-r6-test)]
              (let [adapter-result
                    (if (System/getProperty "babashka.version")
                      {:fail 0 :error 0}
                      (clojure.test/run-tests
                       'credits.engi-r7-adapters-test
                       'credits.engi-webauthn-test
                       'credits.engi-at-client-test
                       'credits.engi-sync-test))]
                (when (pos? (+ (:fail result) (:error result)
                               (:fail r1-result) (:error r1-result)
                               (:fail r2-result) (:error r2-result)
                               (:fail r3-result) (:error r3-result)
                               (:fail r4-result) (:error r4-result)
                               (:fail r5-result) (:error r5-result)
                               (:fail r6-result) (:error r6-result)
                               (:fail adapter-result)
                               (:error adapter-result)))
                  (System/exit 1)))))))))
  )
