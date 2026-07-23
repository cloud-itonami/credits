(ns credits.methods.engi
  "Pure ENGI mutual-credit state machine.

  EN is created by bilateral exchange (equal debit/credit) and retired by
  exchange in the opposite direction.  No administrator, treasury, token
  holder, or validator can mint it.  Commons issuance is a separate,
  explicitly bounded path requiring heterogeneous witnesses.")

(def zero-state
  {:balances {}
   :credit-lines {}
   :used-nonces #{}
   :next-nonce {}
   :seen-event-ids #{}
   :accepted-events []
   :commons-issued-by-epoch {}})

(def required-commons-roles
  #{:local-community :independent-witness :commons-guardian})

(defn balance-of [state did]
  (get-in state [:balances did] 0))

(defn credit-limit-of [state did]
  (get-in state [:credit-lines did :limit] 0))

(defn- rejection [code]
  {:ok? false :error code})

(defn- positive-integer? [x]
  (and (integer? x) (pos? x)))

(defn- signatures-valid?
  "Both economic parties must sign the same event. `verify-signature?` is an
  injected cryptographic verifier so this pure state machine is portable."
  [event verify-signature?]
  (let [required #{(:from event) (:to event)}
        signatures (:signatures event)
        signers (set (map :signer signatures))]
    (and (= required signers)
         (= (count required) (count signatures))
         (every? #(verify-signature? event %) signatures))))

(defn establish-credit-line
  "Establish a participant's negative-balance ceiling from independent
  endorsements. The median of positive endorsements is used; a participant
  cannot endorse themself and duplicate guarantors do not count."
  [state {:keys [id subject endorsements min-guarantors] :as request
          :or {min-guarantors 2}}
   verify-endorsement?]
  (let [valid (->> endorsements
                   (filter #(and (string? (:guarantor %))
                                 (not= subject (:guarantor %))
                                 (positive-integer? (:limit %))
                                 (verify-endorsement? request %)))
                   (reduce (fn [m e] (assoc m (:guarantor e) (:limit e))) {})
                   vals
                   sort
                   vec)]
    (cond
      (not (string? id))
      (rejection :invalid-event-id)

      (contains? (:seen-event-ids state) id)
      (rejection :replayed-event-id)

      (< (count valid) min-guarantors)
      (rejection :insufficient-independent-guarantors)

      :else
      (let [limit (nth valid (quot (count valid) 2))]
        {:ok? true
         :state (-> state
                    (assoc-in [:credit-lines subject]
                              {:limit limit
                               :guarantor-count (count valid)})
                    (update :seen-event-ids conj id)
                    ;; Preserve signed evidence so another client can replay
                    ;; without trusting the client that produced this state.
                    (update :accepted-events conj request))}))))

(defn apply-transfer
  "Apply one two-party EN transfer. Amounts are integer micro-EN. Supply
  remains zero: the payer debit and payee credit are always equal."
  [state {:keys [id from to amount nonce] :as event} verify-signature?]
  (cond
    (or (not (string? id)) (not (string? from)) (not (string? to)))
    (rejection :invalid-identity)

    (= from to)
    (rejection :self-transfer)

    (not (positive-integer? amount))
    (rejection :invalid-amount)

    (not (and (integer? nonce) (not (neg? nonce))))
    (rejection :invalid-nonce)

    (contains? (:used-nonces state) [from nonce])
    (rejection :replayed-nonce)

    (not= nonce (inc (get-in state [:next-nonce from] 0)))
    (rejection :non-contiguous-nonce)

    (contains? (:seen-event-ids state) id)
    (rejection :replayed-event-id)

    (not (signatures-valid? event verify-signature?))
    (rejection :bilateral-signature-required)

    (< (- (balance-of state from) amount)
       (- (credit-limit-of state from)))
    (rejection :credit-limit-exceeded)

    :else
    {:ok? true
     :state (-> state
                (update-in [:balances from] (fnil - 0) amount)
                (update-in [:balances to] (fnil + 0) amount)
                (update :used-nonces conj [from nonce])
                (assoc-in [:next-nonce from] nonce)
                (update :seen-event-ids conj id)
                ;; Signatures are consensus evidence, not transport metadata.
                (update :accepted-events conj event))}))

(defn- heterogeneous-quorum?
  [attestations verify-attestation? event]
  (let [valid (filter #(verify-attestation? event %) attestations)
        roles (set (map :role valid))
        signers (set (map :signer valid))]
    (and (>= (count signers) 4)
         (every? roles required-commons-roles)
         (= (count valid) (count signers)))))

(defn apply-commons-issuance
  "Issue EN for care, ecology, or other work without a direct payer.
  Unlike mutual credit this increases aggregate balances, so it is bounded by
  an epoch cap and requires four distinct signers spanning three roles."
  [state {:keys [id recipient amount epoch epoch-cap attestations] :as event}
   verify-attestation?]
  (let [already (get-in state [:commons-issued-by-epoch epoch] 0)]
    (cond
      (or (not (string? id)) (not (string? recipient)) (nil? epoch))
      (rejection :invalid-commons-event)

      (contains? (:seen-event-ids state) id)
      (rejection :replayed-event-id)

      (or (not (positive-integer? amount))
          (not (positive-integer? epoch-cap)))
      (rejection :invalid-amount)

      (> (+ already amount) epoch-cap)
      (rejection :commons-epoch-cap-exceeded)

      (not (heterogeneous-quorum? attestations verify-attestation? event))
      (rejection :heterogeneous-commons-quorum-required)

      :else
      {:ok? true
       :state (-> state
                  (update-in [:balances recipient] (fnil + 0) amount)
                  (update-in [:commons-issued-by-epoch epoch] (fnil + 0) amount)
                  (update :seen-event-ids conj id)
                  ;; Retain the heterogeneous quorum for independent replay.
                  (update :accepted-events conj event))})))

(defn mutual-credit-net
  "Net balance excluding explicitly disclosed Commons issuance. Must remain 0."
  [state]
  (- (reduce + 0 (vals (:balances state)))
     (reduce + 0 (vals (:commons-issued-by-epoch state)))))

(defn valid-state?
  "Replay invariant used by every client before accepting a checkpoint."
  [state]
  (and (zero? (mutual-credit-net state))
       (every? (fn [[did balance]]
                 (>= balance (- (credit-limit-of state did))))
               (:balances state))))
