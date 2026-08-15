(ns credits.engi-valueflows-test
  "Projection of accepted ENGI events onto the Valueflows plane.

   NOTE: run with `clojure -M:test`. The legacy `run_tests.clj` entry loads
   files explicitly with `load-file` and is deliberately NOT extended to cover
   this namespace — it cannot resolve the ws-valueflo-vocabulary git dependency,
   so adding a line there would break that runner rather than test anything."
  (:require [clojure.test :refer [deftest is testing]]
            [credits.engi.valueflows :as vf]
            [valueflows.event :as vf-event]
            [valueflows.unit :as vf-unit]))

(defn transfer [id from to amount nonce]
  {:id id :from from :to to :amount amount :nonce nonce
   :signatures [{:signer from} {:signer to}]})

(defn issuance [id recipient amount epoch]
  {:id id :recipient recipient :amount amount :epoch epoch :epoch-cap 1000000
   :attestations [{:signer "a"} {:signer "b"} {:signer "c"} {:signer "d"}]})

(defn credit-line [id subject]
  {:id id :subject subject :endorsements [{:guarantor "g" :amount 100}]
   :min-guarantors 1 :revision 1})

;; ── classification ────────────────────────────────────────────────────────

(deftest kinds-are-told-apart-by-shape
  (is (= :transfer (vf/classify (transfer "t1" "alice" "bob" 500 1))))
  (is (= :commons-issuance (vf/classify (issuance "c1" "carol" 300 "e1"))))
  (is (= :credit-line (vf/classify (credit-line "l1" "alice"))))
  (is (= :unclassified (vf/classify {:id "x" :something "else"})))
  (is (= :unclassified (vf/classify "not-a-map"))))

(deftest a-credit-line-is-not-a-transfer-of-nothing
  (is (nil? (vf/->economic-event (credit-line "l1" "alice")))
      "returning a zero-quantity event would put a flow in the ledger that never happened")
  (is (vf/non-economic? :credit-line)))

;; ── one event ─────────────────────────────────────────────────────────────

(deftest a-transfer-becomes-a-two-sided-vf-transfer
  (let [e (vf/->economic-event (transfer "t1" "alice" "bob" 500 1))]
    (is (= :transfer (:action e)))
    (is (= "alice" (:provider e)))
    (is (= "bob" (:receiver e)))
    (is (= "engi-balance:alice" (:resource-inventoried-as e)))
    (is (= "engi-balance:bob" (:to-resource-inventoried-as e)))
    (is (= 500 (:has-numerical-value (:resource-quantity e))))
    (is (= :micro-en (:has-unit (:resource-quantity e))))
    (is (= ["alice" "bob"] (:engi/signers e))
        "the signed evidence is referenced; the kernel remains the authority on it")))

(deftest commons-issuance-becomes-a-raise-not-a-transfer
  ;; It increments one balance with no counterparty, so there is no provider
  ;; side to decrement. `raise` is upstream's adjust-a-quantity-up, and its
  ;; inputOutput is notApplicable, which is right: issuance is not a process step.
  (let [e (vf/->economic-event (issuance "c1" "carol" 300 "e1"))]
    (is (= :raise (:action e)))
    (is (nil? (:provider e)))
    (is (= "carol" (:receiver e)))
    (is (= 300 (:has-numerical-value (:resource-quantity e))))
    (is (= 4 (:engi/attestations e)) "the heterogeneous quorum size stays visible")))

(deftest the-unit-is-micro-en-and-is-registered
  (is (= :micro-en vf/unit))
  (is (vf-unit/registered? vf/unit))
  (is (= :mutual-credit (vf-unit/quantity-kind vf/unit)))
  (is (not (vf-unit/compatible? vf/unit :jpy))
      "EN is not money; a balance sheet must not add them")
  (testing "amounts are not divided into EN"
    (let [e (vf/->economic-event (transfer "t" "a" "b" 1 1))]
      (is (= 1 (:has-numerical-value (:resource-quantity e)))
          "1 micro-EN stays 1, not 0.000001 — integer exactness is the invariant"))))

;; ── projecting a whole journal ─────────────────────────────────────────────

(def journal
  [(credit-line "l1" "alice")
   (transfer "t1" "alice" "bob" 500 1)
   (transfer "t2" "bob" "carol" 200 1)
   (issuance "c1" "carol" 300 "e1")
   (transfer "t3" "carol" "alice" 100 1)])

(deftest projection-reports-what-it-saw-and-what-it-skipped
  (let [p (vf/project journal)]
    (is (:ok? p))
    (is (= 5 (:scanned p)))
    (is (= 4 (count (:events p))) "three transfers and one issuance")
    (is (= {:credit-line 1 :transfer 3 :commons-issuance 1} (:by-kind p)))
    (is (= ["l1"] (:non-economic p)) "a real event with no flow, named rather than dropped")
    (is (:complete? p))))

(deftest an-unknown-shape-is-counted-and-returned
  (let [p (vf/project (conj journal {:id "weird" :some "future-event-kind"}))]
    (is (= 6 (:scanned p)))
    (is (false? (:complete? p)) "a projection that skipped a shape must not look complete")
    (is (= 1 (count (:unclassified p))))
    (is (= "weird" (:id (first (:unclassified p))))
        "returned whole, so the caller can see what it was")))

(deftest an-empty-journal-is-not-a-pass
  (let [p (vf/project [])]
    (is (false? (:ok? p)))
    (is (:empty-input? p))
    (is (false? (:complete? p)))))

;; ── the invariant, carried across the join ─────────────────────────────────

(deftest transfers-leave-net-supply-unchanged
  ;; ENGI's constitutional invariant, expressed on the Valueflows side: a
  ;; transfer creates an equal debit and credit. If the projection broke this,
  ;; the vocabulary layer would tell a different story about the same ledger.
  (let [n (vf/net-supply [(transfer "t1" "alice" "bob" 500 1)
                          (transfer "t2" "bob" "carol" 200 1)
                          (transfer "t3" "carol" "alice" 100 1)])]
    (is (:ok? n))
    (is (= 0 (:total n)) "every debit has an equal credit")
    (is (= 0 (:mutual-credit-net n)))
    (is (= 800 (:transferred n)) "value moved, supply did not")
    (is (= 0 (:issued n)))))

(deftest only-commons-issuance-raises-the-total
  (let [n (vf/net-supply journal)]
    (is (:ok? n))
    (is (= 300 (:total n)) "exactly the issuance")
    (is (= 300 (:issued n)))
    (is (= 0 (:mutual-credit-net n))
        "what the kernel calls mutual-credit-net: everything except disclosed issuance")))

(deftest a-balance-may-go-negative-which-is-the-point-of-mutual-credit
  ;; The projection must not refuse this. A negative balance bounded by
  ;; endorsements is how ENGI works; the bound is the kernel's business.
  (let [n (vf/net-supply [(transfer "t1" "alice" "bob" 500 1)])]
    (is (:ok? n))
    (is (= 0 (:total n)))))

(deftest datoms-land-on-one-dataset-with-coverage
  (let [d (vf/->datoms journal)
        tx (:tx-data d)]
    (is (:ok? d))
    (is (= 1 (count (distinct (map :source/dataset tx)))))
    (is (= "valueflows" (:source/dataset (first tx))))
    (is (= 4 (count (filter :vf.event/action tx))))
    (testing "coverage comes through so a count of datoms cannot read as a whole ledger"
      (is (= 5 (:scanned (:coverage d))))
      (is (= ["l1"] (:non-economic (:coverage d)))))
    (testing "the behaviour cells travel with the event"
      (let [t (first (filter #(= "transfer" (:vf.event/action %)) tx))]
        (is (= "decrementIncrement" (:vf.event/accounting-effect t)))))))

(deftest projection-is-not-admission
  ;; A projection of an event the kernel would REJECT still projects. This is
  ;; deliberate and worth pinning: nothing here verifies a signature, a nonce,
  ;; a credit limit or an epoch cap, and reading a successful projection as
  ;; validation would be the dangerous mistake.
  (let [unsigned {:id "t9" :from "alice" :to "bob" :amount 500 :nonce 1}
        p (vf/project [unsigned])]
    (is (:ok? p) "it projects")
    (is (= 1 (count (:events p))))
    (is (empty? (:engi/signers (first (:events p))))
        "with no signers recorded — the absence is visible, not filled in")))
