(ns {{namespace}}
  "Example PSL Clojure program."
  (:require
   [incanter.core :as in]
   [incanter.io :as ini]
   [clojure.java.io :as io]
   [psl-clojure.core :as psl]
   [clj-util.core :as u]
   )
  (:use
   [incanter.core]
   [incanter.io])
  (:import
   [org.linqs.psl.model.term ConstantType]
   [org.linqs.psl.evaluation.statistics ContinuousEvaluator]
   [org.linqs.psl.database.rdbms.driver H2DatabaseDriver H2DatabaseDriver$Type]
   ))

;;; Predicates

(defn predicates
  "Add predicates to the model"
  [model]
  (psl/add-predicate model knows
                     {"types" (repeat 2 ConstantType/UniqueStringID)
                      "names" ["p1" "p2"]})
  (psl/add-predicate model lived
                     {"types" (repeat 2 ConstantType/UniqueStringID)
                      "names" ["person" "location"]})
  (psl/add-predicate model likes
                     {"types" (repeat 2 ConstantType/UniqueStringID)
                      "names" ["person" "topic"]})
  model)

;;; Rules

(defn rules
  "Add rules to the model"
  [model datastore]
  (psl/add-rule model
                (psl/IMPL
                 (psl/AND
                  (lived 'P1 'L)
                  (lived 'P2 'L)
                  (psl/NEQ 'P1 'P2))
                 (knows 'P1 'P2))
                20 true "nearby-people-known")
  (psl/add-rule model
                (psl/IMPL
                 (psl/AND
                  (lived 'P1 'L1)
                  (lived 'P2 'L2)
                  (psl/NEQ 'P1 'P2)
                  (psl/NEQ 'L1 'L2))
                 (psl/NOT (knows 'P1 'P2)))
                5 true "distant-people-unknown")
  (psl/add-rule model
                (psl/IMPL
                 (psl/AND
                  (likes 'P1 'T)
                  (likes 'P2 'T)
                  (psl/NEQ 'P1 'P2))
                 (knows 'P1 'P2))
                10 true "homophily")
  (psl/add-rule model
                (psl/IMPL
                 (psl/AND
                  (knows 'P1 'P2)
                  (knows 'P2 'P3)
                  (psl/NEQ 'P1 'P3))
                 (knows 'P1 'P3))
                5 true "transitive")
  (psl/add-rule-string model datastore "knows(P1, P2) = knows(P2, P1) .")
  (psl/add-rule model
                (psl/NOT (knows 'P1 'P2))
                5 true "prior")
  model)

;;; Handy functions --- modify to suit

(defn config-bundle
  "A config bundle"
  ([]
   (config-bundle "psl"))
  ([bundle-name]
   (let [cm (. org.linqs.psl.config.ConfigManager getManager)]
     (.getBundle cm bundle-name))))

(defn data-store
  "A data store"
  ([config-bundle]
   (data-store config-bundle true "."))
  ([config-bundle clear-db output-dir]
   (let [db-path (.getPath (io/file output-dir "psl"))
         driver (H2DatabaseDriver. H2DatabaseDriver$Type/Disk db-path clear-db)]
     (org.linqs.psl.database.rdbms.RDBMSDataStore. driver config-bundle))))

(defn inference
  "Run inference"
  [cb ds m obs res closed-preds]
  (let [;; A view for reading observations and writing inferences
        db (psl/open-db ds m [obs] res closed-preds)

        ;; An inference application
        inf (psl/default-inference m db cb)]

    ;; Run inference
    (psl/mpe-inference inf)

    ;; Retrieve ground rules
    (def grs (doall (for [gr (.getGroundRules (.getGroundRuleStore inf))] gr)))

    ;; Clean up
    (psl/close-db db)
    (.close inf)))

(defn evaluate
  "Evaluate inference results"
  [datastore model target-partition truth-partition closed-preds open-pred]
  (let [evaluator (ContinuousEvaluator.)
        rv-db (let [dummy-write (psl/partition-new datastore)]
                (psl/open-db datastore model [target-partition] dummy-write closed-preds))
        truth-db (let [dummy-write (psl/partition-new datastore)]
                   (psl/open-db datastore model [truth-partition] dummy-write closed-preds))
        open-pred (psl/p model open-pred)]
    (.compute evaluator rv-db truth-db open-pred)
    (prn (.toString (.getAllStats evaluator)))
    (psl/close-db rv-db)
    (psl/close-db truth-db)))

(defn example []
  "Run the full example"
  (def cb (config-bundle))
  (def ds (data-store cb))
  (def m (psl/model-new ds))
  (predicates m)
  (rules m ds)
  (psl/model-print m)

  (def knows-obs (ini/read-dataset "data/knows_obs.txt" :delim '\tab :header true))
  (def obs (psl/partition-new ds))
  (psl/pred-append ds obs (psl/p m "knows") knows-obs)
  (prn (psl/pred-read m ds [obs] "knows" true))

  (def likes-obs (ini/read-dataset "data/likes_obs.txt" :delim '\tab :header true))
  (def lived-obs (ini/read-dataset "data/lived_obs.txt" :delim '\tab :header true))
  (psl/pred-append ds obs (psl/p m "likes") likes-obs)
  (psl/pred-append ds obs (psl/p m "lived") lived-obs)

  (def knows-targets (ini/read-dataset "data/knows_targets.txt" :delim '\tab :header true))
  (def target (psl/partition-new ds))
  (psl/pred-append ds target (psl/p m "knows") knows-targets)
  (def closed-preds ["lived" "likes"])
  (inference cb ds m obs target closed-preds)

  (prn (in/sel
        (in/$order [:value] :desc
                   (psl/pred-read m ds [target] "knows" true))
        :rows (range 5)))

  (psl/ground-rules-print-summary
   (psl/ground-rules-stratified-sample grs 1))

  (def knows-truth (ini/read-dataset "data/knows_truth.txt" :delim '\tab :header true))
  (def truth (psl/partition-new ds))
  (psl/pred-append ds truth (psl/p m "knows") knows-truth)
  (evaluate ds m target truth closed-preds "knows")

  (save (in/$order [:value] :desc
                   (psl/pred-read m ds [target] "knows" true))
        "knows.txt"))
