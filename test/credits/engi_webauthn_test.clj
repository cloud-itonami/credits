(ns credits.engi-webauthn-test
  (:require [clojure.data.json :as json]
            [clojure.java.shell :as shell]
            [clojure.test :refer [deftest is]]
            [credits.engi.codec :as codec]
            [credits.engi.crypto :as crypto]
            [credits.engi.webauthn :as webauthn]))

(defn keywordize-assertion [assertion]
  {:credential-id (get assertion "credentialId")
   :authenticator-data (get assertion "authenticatorData")
   :client-data-json (get assertion "clientDataJSON")
   :signature (get assertion "signature")})

(deftest webauthn-proof-is-accepted-by-core-evidence-verifier
  (let [statement {:signer "did:alice"}
        event (codec/with-event-id
               {:type :test-authenticated-action
                :evidence [statement]})
        payload (codec/evidence-payload event statement)
        generated (shell/sh "node" "clients/engi-webauthn.mjs" "fixture"
                            (codec/base64url payload))
        fixture (json/read-str (:out generated))
        assertion (keywordize-assertion (get fixture "assertion"))
        credential {:credential-id (get fixture "credentialId")
                    :public-key-spki (get fixture "publicKeySpki")
                    :origin (get fixture "origin")
                    :rp-id (get fixture "rpId")}
        resolver
        (fn [signer]
          (when (= signer "did:alice")
            {:verify-evidence
             (fn [candidate-payload proof]
               (webauthn/verify-assertion?
                credential candidate-payload proof))}))
        evidence (assoc statement :event-id (:id event) :proof assertion)]
    (is (zero? (:exit generated)))
    (is (crypto/verify-evidence? resolver event evidence))
    (is (not (crypto/verify-evidence?
              resolver event
              (assoc-in evidence [:proof :credential-id] "attacker"))))
    (is (= (:id event)
           (:id (codec/with-event-id
                 (assoc-in event [:evidence 0 :proof] assertion)))))))
