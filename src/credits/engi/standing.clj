(ns credits.engi.standing
  "Plural standing credentials for Commons/Kisha eligibility.

  Ordinary EN exchange needs no personhood credential. Commons distribution
  requires issuer-diverse attestations and an epoch-scoped privacy nullifier so
  one participant cannot claim repeatedly under many DIDs."
  (:require [credits.engi.crypto :as crypto]))

(def required-issuer-roles #{:local-community :independent-issuer})

(defn verify-standing
  [claim epoch used-nullifiers resolve-public-key]
  (let [credentials (:credentials claim)
        valid (filter #(crypto/verify-evidence?
                        resolve-public-key claim %)
                      credentials)
        issuers (set (map :signer valid))
        roles (set (map :issuer-role valid))
        nullifier (:nullifier claim)]
    (cond
      (not= epoch (:epoch claim))
      {:ok? false :error :wrong-standing-epoch}

      (or (not (string? nullifier)) (empty? nullifier))
      {:ok? false :error :missing-nullifier}

      (contains? used-nullifiers [epoch nullifier])
      {:ok? false :error :duplicate-standing-nullifier}

      (< (count issuers) 2)
      {:ok? false :error :issuer-diversity-required}

      (not (every? roles required-issuer-roles))
      {:ok? false :error :issuer-role-quorum-required}

      :else
      {:ok? true
       :used-nullifiers (conj used-nullifiers [epoch nullifier])
       :subject (:subject claim)})))

(defn equal-kisha
  "Equal basic provision. No contribution, reputation, wealth, or Phenotype
  input is accepted by this function."
  [pool eligible-subjects]
  (let [subjects (vec (sort (set eligible-subjects)))]
    (if (or (neg? pool) (empty? subjects))
      {:allocations {} :remainder pool}
      (let [share (quot pool (count subjects))]
        {:allocations (zipmap subjects (repeat share))
         :remainder (- pool (* share (count subjects)))}))))
