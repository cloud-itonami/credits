(ns credits.engi.checkpoint
  "Signed regional checkpoints. Checkpoints accelerate verification but never
  replace event replay or select between conflicting spends."
  (:require [credits.engi.codec :as codec]
            [credits.engi.crypto :as crypto]))

(defn checkpoint
  [{:keys [region epoch state-root event-ids validators threshold]}]
  (codec/with-event-id
   {:type :regional-checkpoint
    :region region
    :epoch epoch
    :state-root state-root
    :event-ids (vec (sort event-ids))
    :threshold threshold
    :signatures (mapv (fn [validator]
                        {:signer validator :role :regional-validator})
                      (sort validators))}))

(defn verify-checkpoint?
  [checkpoint expected-state-root expected-event-ids resolve-public-key]
  (let [valid-signers
        (->> (:signatures checkpoint)
             (filter #(crypto/verify-evidence?
                       resolve-public-key checkpoint %))
             (map :signer)
             set)]
    (and (codec/valid-event-id? checkpoint)
         (= expected-state-root (:state-root checkpoint))
         (= (vec (sort expected-event-ids)) (:event-ids checkpoint))
         (pos-int? (:threshold checkpoint))
         (>= (count valid-signers) (:threshold checkpoint)))))
