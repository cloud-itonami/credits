(ns credits.engi.valueflows
  "Read-only projection of accepted ENGI events onto the Valueflows plane.

   ENGI is mutual credit; Valueflows is the workspace's shared economic
   vocabulary (ADR-2608153000). This namespace is the join between them, so a
   signed EN transfer can be queried alongside a production order or an hour of
   work instead of living in its own island.

   ## What this is NOT

   **PROJECTION IS NOT ADMISSION.** Nothing here verifies a signature, checks a
   nonce, enforces a credit limit or consults an epoch cap. Those are the
   kernel's job (`credits.methods.engi`) and its constitutional boundary is
   untouched: this namespace only reads events the kernel already ACCEPTED, and
   writes nothing. If you find yourself wanting to admit an event here, the
   answer is no.

   ## Kinds are distinguished by shape, not by a tag

   The kernel does not stamp `:accepted-events` with a type. A transfer carries
   `:from`/`:to`/`:amount`/`:nonce`, a Commons issuance carries
   `:recipient`/`:epoch`/`:epoch-cap`, a credit line carries
   `:subject`/`:endorsements`. An event matching none of those is
   `:unclassified` and is COUNTED AND RETURNED, never dropped — a projection
   that silently skipped an event shape would report a smaller ledger that
   looks complete.

   ## The unit is micro-EN

   ENGI amounts are integer micro-EN and stay that way. Dividing by a million
   to present EN turns an exact integer into a fraction, and integer exactness
   is what the zero-net-supply invariant rests on. `:micro-en` is a registered
   unit (`valueflows.unit`) with `:authority :none`, deliberately
   `:mutual-credit` rather than `:currency` so nothing adds it to yen.

   ## Which Valueflows action

   | ENGI                | vf action | why |
   |---------------------|-----------|-----|
   | transfer            | `transfer` | accounting and onhand both decrementIncrement: the payer's balance falls and the payee's rises by the same amount, which is exactly zero net supply |
   | Commons issuance    | `raise`    | increments one balance with no counterparty. `raise` is upstream's \"adjusts a quantity up\", and its `inputOutput` is notApplicable — correct, because issuance is not a process step |
   | credit line         | none       | a standing willingness to hold a negative balance is not a flow. Reported as `:non-economic`, not as an error and not as a transfer of zero |"
  (:require [valueflows.event :as vf-event]
            [valueflows.datom :as vf-datom]
            [valueflows.unit :as vf-unit]))

(def unit
  "The ledger's unit. Registered, so a mismatch against yen is caught."
  :micro-en)

(defn- micro-en [n] {:has-numerical-value n :has-unit unit})

(defn balance-resource
  "The resource id for a participant's EN balance. A participant's balance is
   the economic resource their events increment and decrement."
  [participant]
  (str "engi-balance:" participant))

;; ── classification ────────────────────────────────────────────────────────

(defn classify
  "=> :transfer | :commons-issuance | :credit-line | :unclassified

   By shape, because the kernel does not tag accepted events."
  [e]
  (cond
    (not (map? e)) :unclassified
    (and (:from e) (:to e) (contains? e :amount)) :transfer
    (and (:recipient e) (contains? e :amount) (contains? e :epoch)) :commons-issuance
    (and (:subject e) (contains? e :endorsements)) :credit-line
    :else :unclassified))

(defn non-economic?
  "A credit line changes what a participant MAY do, not what moved."
  [kind]
  (= :credit-line kind))

;; ── one event ─────────────────────────────────────────────────────────────

(defn ->economic-event
  "One accepted ENGI event -> one Valueflows EconomicEvent, or nil when the
   event is not economic. Returns nil rather than a zero-quantity event: a
   credit line is not a transfer of nothing."
  [e]
  (case (classify e)
    :transfer
    {:action :transfer
     :provider (:from e)
     :receiver (:to e)
     :resource-inventoried-as (balance-resource (:from e))
     :to-resource-inventoried-as (balance-resource (:to e))
     :resource-quantity (micro-en (:amount e))
     :note (str "ENGI transfer " (:id e))
     :engi/event-id (:id e)
     :engi/nonce (:nonce e)
     ;; the signed evidence stays referenced, not copied: the kernel is the
     ;; authority on whether it verified
     :engi/signers (mapv :signer (:signatures e))}

    :commons-issuance
    {:action :raise
     :receiver (:recipient e)
     :resource-inventoried-as (balance-resource (:recipient e))
     :resource-quantity (micro-en (:amount e))
     :note (str "ENGI Commons issuance " (:id e) " epoch " (:epoch e))
     :engi/event-id (:id e)
     :engi/epoch (:epoch e)
     :engi/attestations (count (:attestations e))}

    nil))

;; ── many ──────────────────────────────────────────────────────────────────

(defn project
  "Accepted ENGI events -> Valueflows economic events, with coverage.

   => {:ok? true
       :events [...]
       :by-kind {:transfer n :commons-issuance n :credit-line n :unclassified n}
       :non-economic [ids]   ; credit lines: real events, no flow
       :unclassified [events] ; shapes this projection does not know
       :complete? bool}

   `:complete?` is false whenever anything was unclassified. An empty input is
   not a pass: `:empty-input?` is set and `:ok?` is false, because a projection
   of nothing must not read as a ledger with nothing wrong in it."
  [accepted-events]
  (let [kinds (mapv (juxt classify identity) accepted-events)
        by-kind (frequencies (map first kinds))
        unclassified (mapv second (filter #(= :unclassified (first %)) kinds))
        credit-lines (mapv #(:id (second %)) (filter #(= :credit-line (first %)) kinds))
        events (vec (keep #(->economic-event (second %)) kinds))]
    {:ok? (pos? (count accepted-events))
     :empty-input? (zero? (count accepted-events))
     :scanned (count accepted-events)
     :events events
     :by-kind by-kind
     :non-economic credit-lines
     :unclassified unclassified
     :complete? (and (pos? (count accepted-events)) (empty? unclassified))}))

(defn ->datoms
  "Projected events as tx-data for the workspace datom plane. Delegates to
   `valueflows.datom/project`, so the coverage entity and the
   `:source/dataset \"valueflows\"` tag come from there rather than being
   restated."
  [accepted-events]
  (let [p (project accepted-events)]
    (if-not (:ok? p)
      p
      {:ok? true
       :tx-data (vf-datom/project (:events p) {})
       :coverage (select-keys p [:scanned :by-kind :non-economic
                                :unclassified :complete?])})))

;; ── the invariant, carried across the join ────────────────────────────────

(defn net-supply
  "Total micro-EN across all balances after replaying the PROJECTED events
   through `valueflows.event`.

   This is ENGI's constitutional invariant expressed on the other side of the
   join: transfers create an equal debit and credit, so they must leave the
   total unchanged, and only Commons issuance may raise it. If a projection
   ever broke that, the vocabulary layer would be telling a different story
   about the same ledger than the kernel does.

   => {:ok? true :total n :issued n :transferred n :mutual-credit-net n}"
  [accepted-events]
  (let [p (project accepted-events)]
    (if-not (:ok? p)
      p
      (let [r (vf-event/apply-events {} (:events p))]
        (if-not (:ok? r)
          {:ok? false :insufficient :projection-would-not-apply
           :detail {:failed-at (:failed-at r) :errors (:errors r)}}
          (let [total (reduce + 0 (keep #(vf-event/numerical (:inventory r) %
                                                             :accounting-quantity)
                                        (keys (:inventory r))))
                issued (reduce + 0 (map (comp :has-numerical-value :resource-quantity)
                                        (filter #(= :raise (:action %)) (:events p))))]
            {:ok? true
             :total total
             :issued issued
             :transferred (reduce + 0 (map (comp :has-numerical-value :resource-quantity)
                                           (filter #(= :transfer (:action %)) (:events p))))
             ;; what the kernel calls mutual-credit-net: everything except
             ;; disclosed Commons issuance, which must be zero
             :mutual-credit-net (- total issued)
             :unit unit
             :unit-registered? (vf-unit/registered? unit)}))))))
