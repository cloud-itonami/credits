(ns credits.engi.netting
  "Cross-region purchasing-power observation and multilateral netting.

  There is no official oracle or globally privileged price. Each region
  publishes source-diverse living-basket observations; conversion discloses
  integer rounding and settlement remains a signed proposal."
  (:require [credits.engi.codec :as codec]))

(defn median [xs]
  (let [values (vec (sort xs))
        n (count values)]
    (when (pos? n)
      (if (odd? n)
        (nth values (quot n 2))
        (quot (+ (nth values (dec (quot n 2)))
                 (nth values (quot n 2)))
              2)))))

(defn basket-index
  [region epoch observations]
  (let [valid (filter #(and (= region (:region %))
                            (= epoch (:epoch %))
                            (pos-int? (:micro-en %))
                            (string? (:source %)))
                      observations)
        sources (set (map :source valid))]
    (if (< (count sources) 3)
      {:ok? false :error :source-diversity-required}
      {:ok? true :region region :epoch epoch
       :micro-en-per-basket (median (map :micro-en valid))
       :sources (vec (sort sources))})))

(defn convert
  "Convert by observed basket purchasing power. Returns remainder numerator
  instead of hiding rounding."
  [amount from-index to-index]
  (if (or (not (pos-int? amount))
          (not= (:epoch from-index) (:epoch to-index)))
    {:ok? false :error :invalid-conversion}
    (let [numerator (* amount (:micro-en-per-basket to-index))
          denominator (:micro-en-per-basket from-index)]
      {:ok? true
       :amount (quot numerator denominator)
       :rounding-numerator (rem numerator denominator)
       :rounding-denominator denominator})))

(defn net-positions
  "Obligation {:from-region A :to-region B :amount n}. Positive output means
  the region receives; negative means it contributes. Sum is invariantly 0."
  [obligations]
  (reduce
   (fn [positions {:keys [from-region to-region amount]}]
     (-> positions
         (update from-region (fnil - 0) amount)
         (update to-region (fnil + 0) amount)))
   {}
   obligations))

(defn netting-proposal [epoch obligations]
  (let [positions (net-positions obligations)]
    (when-not (zero? (reduce + 0 (vals positions)))
      (throw (ex-info "Unbalanced regional netting" {:positions positions})))
    (codec/with-event-id
     {:type :regional-netting
      :epoch epoch
      :obligation-ids (vec (sort (map :id obligations)))
      :positions positions})))
