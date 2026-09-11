(ns credits.engi.settlement
  "Chain-neutral, read-only settlement claims derived from accepted ENGI evidence.
  Claims are projections: the replay kernel never admits them as balance events."
  (:require [credits.engi.codec :as codec]))

(defn- positive-integer? [value]
  (and (integer? value) (pos? value)))

(defn- claim [body]
  (assoc body :claim/id (codec/event-id body)))

(defn reserve-claim
  [{:keys [checkpoint-root checkpoint-sequence bridge-did locked-micro-en event-ids]}]
  (when-not (and (string? checkpoint-root)
                 (positive-integer? checkpoint-sequence)
                 (string? bridge-did)
                 (not-empty bridge-did)
                 (integer? locked-micro-en)
                 (not (neg? locked-micro-en))
                 (sequential? event-ids)
                 (every? string? event-ids))
    (throw (ex-info "Invalid reserve claim" {:type :invalid-reserve-claim})))
  (claim {:claim/type :engi.settlement/reserve-v1
          :checkpoint/root checkpoint-root
          :checkpoint/sequence checkpoint-sequence
          :bridge/did bridge-did
          :amount/unit :micro-en
          :amount/value locked-micro-en
          :evidence/event-ids (vec (sort event-ids))}))

(defn deposit-claim
  [{:keys [event checkpoint-root checkpoint-sequence bridge-did amount]}]
  (when-not (and (= :transfer (:type event))
                 (codec/valid-event-id? event)
                 (= bridge-did (:to event))
                 (string? checkpoint-root)
                 (positive-integer? checkpoint-sequence)
                 (positive-integer? amount)
                 (<= amount (:amount event)))
    (throw (ex-info "Invalid deposit claim" {:type :invalid-deposit-claim})))
  (claim {:claim/type :engi.settlement/deposit-v1
          :deposit/event-id (:id event)
          :checkpoint/root checkpoint-root
          :checkpoint/sequence checkpoint-sequence
          :bridge/did bridge-did
          :amount/unit :micro-en
          :amount/value amount}))

(defn release-claim
  [{:keys [event bridge-did withdrawal-ref]}]
  (when-not (and (= :transfer (:type event))
                 (codec/valid-event-id? event)
                 (= bridge-did (:from event))
                 (string? withdrawal-ref)
                 (not-empty withdrawal-ref))
    (throw (ex-info "Invalid release claim" {:type :invalid-release-claim})))
  (claim {:claim/type :engi.settlement/release-v1
          :release/event-id (:id event)
          :bridge/did bridge-did
          :engi/recipient (:to event)
          :amount/unit :micro-en
          :amount/value (:amount event)
          :adapter/withdrawal-ref withdrawal-ref}))
