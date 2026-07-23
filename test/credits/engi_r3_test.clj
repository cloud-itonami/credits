(ns credits.engi-r3-test
  (:require [clojure.test :refer [deftest is testing]]
            [credits.engi.checkpoint :as checkpoint]
            [credits.engi.codec :as codec]
            [credits.engi.crypto :as crypto]
            [credits.engi.relay :as relay]
            [credits.engi.replay :as replay]
            [credits.engi.store :as store]))

(def actors ["did:alice" "did:bob" "did:carol"
             "did:validator-1" "did:validator-2" "did:validator-3"])
(def keys-by-did (zipmap actors (repeatedly crypto/generate-keypair)))
(defn public-key [did] (get-in keys-by-did [did :public-key]))

(defn signed-event [body evidence-key evidence]
  (let [event (codec/with-event-id (assoc body evidence-key evidence))]
    (assoc event evidence-key
           (mapv (fn [statement]
                   (crypto/sign-evidence
                    event statement
                    (get-in keys-by-did
                            [(:signer statement) :private-key])))
                 evidence))))

(def line-event
  (signed-event
   {:type :credit-line :subject "did:alice" :parents []}
   :endorsements
   [{:signer "did:bob" :guarantor "did:bob"
     :subject "did:alice" :limit 100}
    {:signer "did:carol" :guarantor "did:carol"
     :subject "did:alice" :limit 80}]))

(def transfer-event
  (signed-event
   {:type :transfer :from "did:alice" :to "did:bob"
    :amount 40 :nonce 1 :parents [(:id line-event)]}
   :signatures [{:signer "did:alice"} {:signer "did:bob"}]))

(deftest participant-journal-survives-restart-and-detects-corruption
  (let [file (java.io.File/createTempFile "engi-participant-" ".edn")
        path (.getAbsolutePath file)]
    (.delete file)
    (try
      (is (:ok? (store/append-event! path line-event)))
      (is (:ok? (store/append-event! path transfer-event)))
      (is (:duplicate? (store/append-event! path transfer-event)))
      (let [restarted (store/load-events path)
            result (replay/replay (:events restarted) public-key)]
        (is (:ok? restarted))
        (is (= [(:id line-event) (:id transfer-event)]
               (mapv :id (:events restarted))))
        (is (:ok? result))
        (is (= -40 (get-in result [:state :balances "did:alice"]))))
      (spit path "{:id \"forged\"}\n" :append true)
      (is (= :event-id-mismatch (:error (store/load-events path))))
      (finally (.delete (java.io.File. path))))))

(deftest either-of-two-relays-can-disappear
  (let [r1 (relay/publish (relay/empty-relay "r1")
                          [line-event transfer-event])
        r2 (relay/publish (relay/empty-relay "r2")
                          [line-event transfer-event])
        from-r1 (relay/union-from-relays [r1])
        from-r2 (relay/union-from-relays [r2])
        combined (relay/union-from-relays [r2 r1])]
    (is (:ok? r1))
    (is (:ok? r2))
    (is (= (mapv :id (:events from-r1))
           (mapv :id (:events from-r2))
           (mapv :id (:events combined))))))

(deftest signed-checkpoint-is-evidence-not-an-unverified-snapshot
  (let [result (replay/replay [line-event transfer-event] public-key)
        unsigned
        (checkpoint/checkpoint
         {:region "jp-kanto-1" :epoch 1
          :state-root (:state-root result)
          :event-ids (mapv :id [line-event transfer-event])
          :validators ["did:validator-1" "did:validator-2"
                       "did:validator-3"]
          :threshold 2})
        signed
        (update unsigned :signatures
                (fn [statements]
                  (mapv
                   (fn [statement]
                     (if (#{"did:validator-1" "did:validator-2"}
                           (:signer statement))
                       (crypto/sign-evidence
                        unsigned statement
                        (get-in keys-by-did
                                [(:signer statement) :private-key]))
                       statement))
                   statements)))]
    (is (checkpoint/verify-checkpoint?
         signed (:state-root result)
         (mapv :id [line-event transfer-event]) public-key))
    (testing "checkpoint cannot bless a different replay result"
      (is (not (checkpoint/verify-checkpoint?
                signed "en1:wrong"
                (mapv :id [line-event transfer-event]) public-key))))
    (testing "one regional validator is insufficient"
      (let [one
            (update unsigned :signatures
                    (fn [statements]
                      (mapv
                       (fn [statement]
                         (if (= "did:validator-1" (:signer statement))
                           (crypto/sign-evidence
                            unsigned statement
                            (get-in keys-by-did
                                    ["did:validator-1" :private-key]))
                           statement))
                       statements)))]
        (is (not (checkpoint/verify-checkpoint?
                  one (:state-root result)
                  (mapv :id [line-event transfer-event]) public-key)))))))
