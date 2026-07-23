(ns credits.engi.codec
  "Canonical ENGI event encoding and content identifiers.

  The accepted value domain is deliberately smaller than arbitrary EDN:
  nil, booleans, strings, keywords, integers, vectors, and maps. Maps are
  recursively key-sorted. Floats, sets, symbols, tagged values, and lists are
  rejected so two implementations cannot silently encode them differently."
  (:import (java.nio.charset StandardCharsets)
           (java.security MessageDigest)
           (java.util Base64)))

(def protocol-version 1)

(defn- key-compare [a b]
  (compare (pr-str a) (pr-str b)))

(declare canonical-value)

(defn canonical-value [value]
  (cond
    (or (nil? value) (boolean? value) (string? value)
        (keyword? value) (integer? value))
    value

    (vector? value)
    (mapv canonical-value value)

    (map? value)
    (into (sorted-map-by key-compare)
          (map (fn [[k v]]
                 [(canonical-value k) (canonical-value v)]))
          value)

    :else
    (throw (ex-info "Value is outside canonical ENGI EDN domain"
                    {:value value :type (type value)}))))

(defn canonical-string [value]
  (pr-str (canonical-value value)))

(defn canonical-bytes [value]
  (.getBytes ^String (canonical-string value) StandardCharsets/UTF_8))

(defn hex [^bytes bs]
  (apply str (map #(format "%02x" (bit-and 0xff %)) bs)))

(defn sha256 [^bytes bs]
  (.digest (MessageDigest/getInstance "SHA-256") bs))

(def proof-keys #{:signature :event-id :proof})

(defn without-proof [value]
  (cond
    (map? value)
    (into {}
          (keep (fn [[k v]]
                  (when-not (proof-keys k)
                    [k (without-proof v)])))
          value)

    (vector? value)
    (mapv without-proof value)

    :else value))

(defn event-id-input
  "The immutable economic content. Signatures and their event-id copies are
  excluded, while signer, role, guarantor, and limit remain bound."
  [event]
  (-> event
      (dissoc :id)
      without-proof
      (assoc :protocol/version protocol-version)))

(defn event-id [event]
  (str "en1:" (hex (sha256 (canonical-bytes (event-id-input event))))))

(defn with-event-id [event]
  (assoc event :id (event-id event)))

(defn valid-event-id? [event]
  (= (:id event) (event-id event)))

(defn evidence-payload
  "Bytes signed by one transfer party, guarantor, or Commons witness. The
  evidence statement itself is bound, but all signatures are removed."
  [event evidence]
  (canonical-bytes
   {:protocol/version protocol-version
    :event (without-proof event)
    :evidence (without-proof evidence)}))

(def ^:private missing (Object.))

(declare merge-proof-values)

(defn- proof-alternatives [value]
  (if (vector? value) value [value]))

(defn- merge-proof-field [key left right]
  (if (= key :event-id)
    {:ok? false :error :conflicting-event-id-copy}
    {:ok? true
     :value (->> (concat (proof-alternatives left)
                         (proof-alternatives right))
                 (reduce (fn [values candidate]
                           (assoc values (canonical-string candidate)
                                  candidate))
                         (sorted-map))
                 vals
                 vec)}))

(defn- merge-proof-maps [left right]
  (reduce
   (fn [result key]
     (if-not (:ok? result)
       (reduced result)
       (let [left-value (get left key missing)
             right-value (get right key missing)]
         (cond
           (proof-keys key)
           (cond
             (identical? left-value missing)
             (assoc-in result [:value key] right-value)
             (identical? right-value missing) result
             (= left-value right-value) result
             :else
             (let [merged (merge-proof-field key left-value right-value)]
               (if (:ok? merged)
                 (assoc-in result [:value key] (:value merged))
                 (reduced merged))))

           (or (identical? left-value missing)
               (identical? right-value missing))
           (reduced {:ok? false :error :economic-content-mismatch})

           :else
           (let [merged (merge-proof-values left-value right-value)]
             (if (:ok? merged)
               (assoc-in result [:value key] (:value merged))
               (reduced merged)))))))
   {:ok? true :value left}
   (into #{} (concat (keys left) (keys right)))))

(defn merge-proof-values [left right]
  (cond
    (= left right) {:ok? true :value left}
    (and (map? left) (map? right)) (merge-proof-maps left right)
    (and (vector? left) (vector? right) (= (count left) (count right)))
    (reduce
     (fn [result [index [left-value right-value]]]
       (if-not (:ok? result)
         (reduced result)
         (let [merged (merge-proof-values left-value right-value)]
           (if (:ok? merged)
             (assoc-in result [:value index] (:value merged))
             (reduced merged)))))
     {:ok? true :value left}
     (map-indexed vector (map vector left right)))
    :else {:ok? false :error :economic-content-mismatch}))

(defn merge-proof-enrichment
  "Merge two representations of one economic event. Only missing proof fields
  may be added; economic content and competing proof bytes never overwrite."
  [left right]
  (cond
    (or (not (valid-event-id? left)) (not (valid-event-id? right)))
    {:ok? false :error :event-id-mismatch}

    (not= (:id left) (:id right))
    {:ok? false :error :different-event-id}

    (not= (canonical-string (event-id-input left))
          (canonical-string (event-id-input right)))
    {:ok? false :error :event-id-collision}

    :else
    (let [merged (merge-proof-values left right)]
      (if (:ok? merged)
        {:ok? true :event (:value merged)}
        (assoc merged :event-id (:id left))))))

(defn base64url [^bytes bs]
  (.encodeToString (.withoutPadding (Base64/getUrlEncoder)) bs))

(defn base64url-decode [^String s]
  (.decode (Base64/getUrlDecoder) s))
