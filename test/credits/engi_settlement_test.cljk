(ns credits.engi-settlement-test
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [credits.engi.replay :as replay]
            [credits.engi.settlement :as settlement]))

(def corpus
  (-> "resources/engi/example-journal.edn" io/file slurp edn/read-string))

(def replayed
  (replay/replay (:corpus/events corpus) (:corpus/public-keys corpus)))

(def deposit
  (first (filter #(= "en1:8c475082e801710e6e114f89b4f9aa1d157ee480ee1b09199e86763cc5aa8cff"
                     (:id %))
                 (:corpus/events corpus))))

(def release
  (first (filter #(= "en1:f0ec01432b55e7932f113b6433b546f4cdefeaa27db5194b3faf462aed568776"
                     (:id %))
                 (:corpus/events corpus))))

(deftest chain-neutral-claims-use-replayed-engi-evidence
  (let [root (:state-root replayed)
        reserve (settlement/reserve-claim
                 {:checkpoint-root root
                  :checkpoint-sequence 1
                  :bridge-did "did:bob"
                  :locked-micro-en (get-in replayed [:state :balances "did:bob"])
                  :event-ids (map :id (:corpus/events corpus))})
        deposit-claim (settlement/deposit-claim
                       {:event deposit :checkpoint-root root :checkpoint-sequence 1
                        :bridge-did "did:bob" :amount 10})
        release-claim (settlement/release-claim
                       {:event release :bridge-did "did:bob"
                        :withdrawal-ref "adapter-receipt:example"})]
    (is (:ok? replayed))
    (is (= 35 (:amount/value reserve)))
    (is (= (:id deposit) (:deposit/event-id deposit-claim)))
    (is (= (:id release) (:release/event-id release-claim)))
    (is (every? :claim/id [reserve deposit-claim release-claim]))
    (testing "the canonical claims contain no VM, chain, gas, RPC or ABI fields"
      (is (not-any? #(contains? reserve %)
                    [:evm/address :fevm/address :chain/id :transaction/hash
                     :gas/limit :solidity/abi])))))

(deftest projection-refuses-evidence-with-wrong-bridge-direction
  (is (thrown? Exception
               (settlement/deposit-claim
                {:event deposit :checkpoint-root (:state-root replayed)
                 :checkpoint-sequence 1 :bridge-did "did:alice" :amount 10})))
  (is (thrown? Exception
               (settlement/release-claim
                {:event release :bridge-did "did:alice"
                 :withdrawal-ref "adapter-receipt:example"}))))
