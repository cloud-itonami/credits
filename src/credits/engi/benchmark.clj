(ns credits.engi.benchmark
  "Deterministic large-journal benchmark without I/O or network."
  (:require [credits.methods.engi :as engi]))

(defn run-transfers [n]
  (let [start (System/nanoTime)
        signature-ok? (constantly true)
        initial
        (:state
         (engi/establish-credit-line
          engi/zero-state
          {:id "credit-line:bench" :type :credit-line
           :subject "did:a"
           :endorsements
           [{:guarantor "did:g1" :limit n}
            {:guarantor "did:g2" :limit n}]}
          (constantly true)))
        final
        (loop [state initial nonce 1]
          (if (> nonce n)
            state
            (let [event {:id (str "tx:" nonce) :type :transfer
                         :from "did:a" :to "did:b"
                         :amount 1 :nonce nonce
                         :parents (cond-> ["credit-line:bench"]
                                    (> nonce 1)
                                    (conj (str "tx:" (dec nonce))))
                         :signatures [{:signer "did:a"}
                                      {:signer "did:b"}]}
                  result (engi/apply-transfer state event signature-ok?)]
              (when-not (:ok? result)
                (throw (ex-info "benchmark transition rejected" result)))
              (recur (:state result) (inc nonce)))))
        elapsed-ms (/ (- (System/nanoTime) start) 1000000.0)]
    {:event-count n
     :elapsed-ms elapsed-ms
     :events-per-second (long (/ n (/ elapsed-ms 1000.0)))
     :valid-state? (engi/valid-state? final)
     :alice-balance (engi/balance-of final "did:a")
     :bob-balance (engi/balance-of final "did:b")}))
