(ns credits.dev.calibration-bridge-evidence
  "Verify and print the committed synthetic ENGI evidence used by the
  Filecoin Calibration plumbing test. This never represents production escrow."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [credits.engi.replay :as replay]))

(def test-bridge-did "did:bob")
(def deposit-event-id
  "en1:8c475082e801710e6e114f89b4f9aa1d157ee480ee1b09199e86763cc5aa8cff")
(def release-event-id
  "en1:f0ec01432b55e7932f113b6433b546f4cdefeaa27db5194b3faf462aed568776")

(defn- as-bytes32 [event-id]
  (str "0x" (subs event-id 4)))

(defn -main [& _]
  (let [corpus (-> "resources/engi/example-journal.edn" io/file slurp edn/read-string)
        public-keys (:corpus/public-keys corpus)
        replayed (replay/replay (:corpus/events corpus) public-keys)
        expected-root (:corpus/state-root corpus)
        locked (get-in replayed [:state :balances test-bridge-did])]
    (when-not (and (:ok? replayed)
                   (= expected-root (:state-root replayed))
                   (pos-int? locked))
      (throw (ex-info "Synthetic ENGI evidence did not replay" replayed)))
    (println "EVIDENCE_KIND=cryptographically-replayed-synthetic-corpus")
    (println (str "CHECKPOINT_ROOT=" (as-bytes32 (:state-root replayed))))
    (println (str "TEST_BRIDGE_DID=" test-bridge-did))
    (println (str "LOCKED_MICRO_EN=" locked))
    (println (str "DEPOSIT_ID=" (as-bytes32 deposit-event-id)))
    (println (str "RELEASE_EVENT_ID=" (as-bytes32 release-event-id)))))
