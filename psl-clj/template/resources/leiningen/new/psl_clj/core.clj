(ns {{namespace}}
  "Example PSL Clojure program."
  (:require
   [incanter.core :as in]
   [clojure.java.io :as io]
   [psl-clj.core :as psl]
   [clj-util.core :as u]
   )
  (:import
   [org.linqs.psl.model.term ConstantType]
   [org.linqs.psl.database.rdbms.driver H2DatabaseDriver H2DatabaseDriver$Type]
   ))

;;; Predicates

(defn VOCAB
  "Add predicates to the model"
  [model]
  (psl/add-predicate model dog
                     {"types" (repeat 1 ConstantType/UniqueStringID)
                      "names" ["n"]})
  (psl/add-predicate model mammal
                     {"types" (repeat 1 ConstantType/UniqueStringID)
                      "names" ["n"]})
  model)

;;; Rules

(defn DOGS-ARE-MAMMALS
  "Add a rule that all dogs are mammals."
  [model weight squared]
  (psl/add-rule model
                (psl/IMPL
                 (dog 'N)
                 (mammal 'N))
                weight squared "DOGS-ARE-MAMMALS")
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

(defn example []
  "Run the full example"
  (def cb (config-bundle))
  (def ds (data-store cb))
  (def m (psl/model-new ds))
  (VOCAB m)
  (DOGS-ARE-MAMMALS m 1.0 false)
  (def dogs (in/dataset [:n] [["Fido"] ["Furry"]]))
  (def obs (psl/partition-new))
  (psl/pred-append ds obs (psl/p m "dog") dogs)
  (prn (psl/pred-read m ds [obs] "dog" true))
  (def res (psl/partition-new))
  (inference cb ds m obs res ["dog"])
  (def more-dogs (in/dataset [:n :value] [["George" 0.5]]))
  (psl/pred-append ds obs (psl/p m "dog") more-dogs)
  (inference cb ds m obs res ["dog"])
  (prn (psl/pred-read m ds [res] "mammal" true))
  (psl/add-rule m (psl/NOT (mammal 'N)) 0.75 true "MAMMALS ARE RARE")
  (inference cb ds m obs res ["dog"])
  (prn (psl/pred-read m ds [res] "mammal" true))
  (psl/ground-rules-print-summary grs))
