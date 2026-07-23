(require 'clojure.test)
(load-file "src/credits/methods/engi.cljc")
(load-file "methods/test_engi.cljc")
(load-file "src/credits/engi/codec.clj")
(load-file "src/credits/engi/crypto.clj")
(load-file "src/credits/engi/replay.clj")
(load-file "test/credits/engi_r1_test.clj")
(let [result (clojure.test/run-tests 'credits.methods.test-engi)]
  (let [r1-result (clojure.test/run-tests 'credits.engi-r1-test)]
    (when (pos? (+ (:fail result) (:error result)
                   (:fail r1-result) (:error r1-result)))
    (System/exit 1)))
  )
