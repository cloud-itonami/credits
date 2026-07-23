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

(defn- without-signature [value]
  (cond
    (map? value)
    (into {}
          (keep (fn [[k v]]
                  (when-not (#{:signature :event-id :proof} k)
                    [k (without-signature v)])))
          value)

    (vector? value)
    (mapv without-signature value)

    :else value))

(defn event-id-input
  "The immutable economic content. Signatures and their event-id copies are
  excluded, while signer, role, guarantor, and limit remain bound."
  [event]
  (-> event
      (dissoc :id)
      without-signature
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
    :event (without-signature event)
    :evidence (without-signature evidence)}))

(defn base64url [^bytes bs]
  (.encodeToString (.withoutPadding (Base64/getUrlEncoder)) bs))

(defn base64url-decode [^String s]
  (.decode (Base64/getUrlDecoder) s))
