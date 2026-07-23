(ns credits.engi.identity
  "Social device-key recovery without an etzhayyim administrator."
  (:require [credits.engi.crypto :as crypto]
            [credits.engi.codec :as codec]))

(defn identity-state
  [{:keys [did active-public-key guardians threshold]}]
  {:did did
   :active-public-key active-public-key
   :guardians (set guardians)
   :threshold threshold
   :rotation-seq 0
   :revoked-public-keys #{}})

(defn rotation-body [identity new-public-key]
  {:type :device-key-rotation
   :did (:did identity)
   :old-public-key (:active-public-key identity)
   :new-public-key new-public-key
   :rotation-seq (inc (:rotation-seq identity))})

(defn rotation-event [identity new-public-key]
  (codec/with-event-id
   (assoc (rotation-body identity new-public-key)
          :attestations
          (mapv (fn [guardian]
                  {:signer guardian :role :recovery-guardian})
                (sort (:guardians identity))))))

(defn apply-rotation
  [identity event resolve-guardian-public-key]
  (let [valid (->> (:attestations event)
                   (filter
                    (fn [attestation]
                      (and (contains? (:guardians identity)
                                      (:signer attestation))
                           (crypto/verify-evidence?
                            resolve-guardian-public-key event attestation))))
                   (map :signer)
                   set)]
    (cond
      (not (codec/valid-event-id? event))
      {:ok? false :error :event-id-mismatch}

      (not= (:did identity) (:did event))
      {:ok? false :error :wrong-identity}

      (not= (:active-public-key identity) (:old-public-key event))
      {:ok? false :error :stale-or-forked-key}

      (not= (inc (:rotation-seq identity)) (:rotation-seq event))
      {:ok? false :error :non-contiguous-rotation}

      (< (count valid) (:threshold identity))
      {:ok? false :error :guardian-quorum-required}

      :else
      {:ok? true
       :identity
       (-> identity
           (update :revoked-public-keys conj (:active-public-key identity))
           (assoc :active-public-key (:new-public-key event)
                  :rotation-seq (:rotation-seq event)))})))
