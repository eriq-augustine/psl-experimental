(ns psl-clojure.core
  "A clojure user interface for PSL"
  (:require
   [clj-util.core :as cu]
   [clojure.data.csv :as csv]
   [clojure.java.io :as io]
   [clojure.string :as cljs]
   [clojure.tools.logging :as log]
   [incanter.core :as in]
   [psl-clojure.Model]
   )
  (:import
   [psl_clojure Model]
   [org.linqs.psl.application.groundrulestore AtomRegisterGroundRuleStore]
   [org.linqs.psl.application.inference MPEInference LazyMPEInference]
   [org.linqs.psl.application.util GroundRules Grounding]
   [org.linqs.psl.database DataStore Database Partition Queries]
   [org.linqs.psl.database.atom PersistedAtomManager]
   [org.linqs.psl.database.rdbms RDBMSDataStore]
   [org.linqs.psl.model.atom QueryAtom]
   [org.linqs.psl.model.formula Conjunction Disjunction Negation Formula Implication]
   [org.linqs.psl.model.rule UnweightedGroundRule WeightedGroundRule]
   [org.linqs.psl.model.rule.logical UnweightedGroundLogicalRule
    UnweightedLogicalRule WeightedLogicalRule]
   [org.linqs.psl.model.term UniqueStringID Variable Term Constant]
   [org.linqs.psl.parser ModelLoader]
   [java.util HashSet]
   ))

;;; ================== Building PSL models ===========================

(defn AND
  "Return a PSL conjunction formula over given formulas."
  [& args]
  (Conjunction. (into-array Formula args)))

(defn OR
  "Return a PSL disjunction formula over given formulas."
  [& args]
  (Disjunction. (into-array Formula args)))

(defn IMPL
  "Return a PSL rule formula for the given body and head formulas."
  [body head]
  (Implication. body head))

(defn NOT
  "Return a PSL negation formula over the given formula."
  [formula]
  (Negation. formula))

(defn NEQ [term1 term2]
  "Return a PSL functional predicate stating that term1 != term2."
  (QueryAtom.
   org.linqs.psl.model.predicate.SpecialPredicate/NotEqual
   (into-array
    Term
    (for [a [term1 term2]]
      (if (symbol? a) (Variable. (name a)) a)))))

(defmacro add-predicate
  "Add the specified predicate to the model."
  [model pred-name argmap]
  `(do
     (.add ~model                     ; Add the predicate to the model
           (java.util.HashMap.
            (assoc ~argmap "predicate" (name '~pred-name)))) ; Add 'predicate'
     (defn ~pred-name [& ~'args]    ; A function returning a QueryAtom
       (QueryAtom.
        (.getPredicate ~model (name '~pred-name)) ; Predicate from name
        (into-array                               ; Array of Terms
         Term
         (for [~'a ~'args]
           (if (symbol? ~'a) (Variable. (name ~'a)) ~'a)))))))

(defmacro add-pred-constraint
  "Add a predicate constraint to the model."
  [model predicate constraint-type cname]
  `(do
     (.addConstraint
      ~model
      ~constraint-type
      {"on" (.getPredicate ~model (name '~predicate))
       "name" ~cname})))

(defn add-rule [model formula weight squared name]
  "Add a logical rule to the model."
  (let [rule (WeightedLogicalRule. formula weight squared name)]
    (.addRule model rule)))

(defn add-rule-string
  "Add a rule formatted as a string to the model."
  ([model datastore rule-string weight squared name]
   (let [partial-rule (ModelLoader/loadRulePartial datastore rule-string)
         rule (do (assert (not (.isRule partial-rule)))
                  (.toRule partial-rule weight squared))]
     (.setName rule name)
     (.addRule model rule)))
  ([model datastore rule-string name]
   (let [partial-rule (ModelLoader/loadRulePartial datastore rule-string)
         rule (do (assert (.isRule partial-rule))
                  (.toRule partial-rule))]
     (.setName rule name)
     (.addRule model rule))))

;;; =============== Other functions for using PSL ====================

(defn close-db
  "Close Database, catching an IllegalStateException if it is already closed."
  [database]
  (try
    (.close database)
    (catch IllegalStateException e (str "caught exception: " (.getMessage e)))))

(defn close-open-dbs "Close all open databases and returns their count."
  [datastore]
  (let [dbs (.getOpenDatabases datastore)
        num (count dbs)]
    (doseq [d dbs] (close-db d))
    num))

(defn default-inference
  "Return an app for MPE inference using the configuration."
  [model database config-bundle]
  (LazyMPEInference. model database config-bundle))

(defn ground-rules-by-name
  "Return all ground rules from a given collection with the given name."
  [ground-rules rule-name]
  (for [gr ground-rules :when (= (.getName (.getRule gr)) rule-name)] gr))

(defn ground-rules-by-name-sort
  "Return a list of ground rules sorted by rule name."
  [ground-rules]
  (sort (fn [g1 g2]
          (let [n1 (.getName (.getRule g1))
                n2 (.getName (.getRule g2))]
            (compare n1 n2)))
        (seq ground-rules)))

(defn ground-rules-names
  "Return a list of distinct rule names associated with the given
  collection of ground rules."
  [ground-rules]
  (sort (distinct (for [gr ground-rules] (.getName (.getRule gr))))))

(defn ground-rules-print-summary
  "Print a summary of ground rules."
  ;; Without rule name
  ([ground-rules]
   (doseq [gr (ground-rules-by-name-sort ground-rules)]
     (cu/println-center (.getName (.getRule gr)) "=" 79)
     (println (cond (instance? UnweightedGroundRule gr)
                    (str "INFE: " (.getInfeasibility gr))
                    (instance? WeightedGroundRule gr)
                    (str "INCO: " (.getIncompatibility gr))))
     (println (str "CLAS: " (.getSimpleName (.getClass gr))))
     (cu/printlnw (str "STRI: " gr) 79)
     (doseq [a (.getAtoms gr)]
       (println (str "ATOM: " (.getValue a) ":" a)))))
  ;; With rule name
  ([ground-rules rule-name]
   (ground-rules-print-summary
    (ground-rules-by-name ground-rules rule-name))))

(defn ground-rules-sample
  "Return a sample of the ground rules up to a maximum size n."
  [ground-rules n]
  (cu/random-sample-n (seq ground-rules) n))

(defn ground-rules-stratified-sample
  "Return a sample of ground rules, with up to n of each rule."
  [ground-rules n]
  (for [rule-name (ground-rules-names ground-rules)
        ground-rule (ground-rules-sample
                     (ground-rules-by-name ground-rules rule-name)
                     n)]
    ground-rule))

(defn rules-info
  "Return info about rules."
  [model]
  (for [k (.getRules model)]
    (if (instance? UnweightedLogicalRule k)
      {:kind "constraint" :name (.getName k)}
      {:kind "compatibility" :name (.getName k) :weight (.getWeight k)})))

(defn model-new
  "Return a new PSLModel associated with the given DataStore."
  [data-store]
  (Model. "" data-store))

(defn model-print
  "Print the given model."
  [model]
  (println (.toString model)))

(defn model-load
  "Return a model loaded from a file.  The given datastore must
  already have all the predicates used.  See model-save."
  [datastore model-file-path]
  (let [model-string (slurp model-file-path)
        ;; Work-around for PSL.g4 and Negation.toString issue
        model-string (cljs/replace model-string #"~\( (.+) \)" "~$1")
        model (model-new datastore)
        ]
    (.addRules model model-string)
    model))

(defn model-save
  "Save the model to a file."
  [model model-file-path]
  (spit model-file-path (.asString model)))

(defn mpe-inference
  "Call mpeInference on the inference app."
  [inference-app]
  (log/info "inference:: ::starting")
  (let [result (.mpeInference inference-app)]
    (log/info "inference:: ::done")
    result))

(defn open-db
  "Return an open PSL database."
  ([datastore model parts-to-read part-to-write preds-to-close]
     {:pre [(not-any? nil? [datastore model parts-to-read part-to-write preds-to-close])]}
     (let [parts-to-read (into-array Partition parts-to-read)
           preds-to-close (HashSet.
                           (for [pname preds-to-close]
                             (.getPredicate model pname)))]
       (.getDatabase datastore part-to-write preds-to-close parts-to-read)))

  ;; No predicates to close
  ([datastore model parts-to-read part-to-write]
     {:pre [(not-any? nil? [datastore model parts-to-read part-to-write])]}
     (let [parts-to-read (into-array Partition parts-to-read)]
       (.getDatabase datastore part-to-write parts-to-read)))

  ;; No predicates to close or write partition
  ([datastore model parts-to-read]
     {:pre [(not-any? nil? [datastore model parts-to-read])]}
     (let [parts-to-read (into-array Partition parts-to-read)]
       (.getDatabase datastore (first parts-to-read) parts-to-read))))

(defn p
  "Get a predicate object from the PSL model"
  [model pred-name]
  (.getPredicate model pred-name))

;;; =============== Functions for handling PSL partitions ====================

(defn partition-copy-atoms
  "Copy atoms of given predicates from one partition to another"
  [datastore model part-from part-to preds]
  (let [dbr (open-db datastore model [part-from])
        dbw (open-db datastore model [part-to] part-to)]
    (try
      (doseq [pnam preds]
        (let [atoms (Queries/getAllAtoms dbr (p model pnam))]
          (doseq [atom atoms]
            (.commit dbw atom))))
      nil
      (finally (close-db dbr)
               (close-db dbw)))))

(defn partition-delete
  "Delete the contents of the partition in the datastore."
  [datastore partition] (.deletePartition datastore partition))

(defn partitions-delete
  "Delete the contents of the partitions in the datastore."

  ;; Selected partitions
  ([datastore partitions]
   (doseq [partition partitions]
     (partition-delete datastore partition))
   (count partitions))

  ;; All partitions
  ([datastore]
   (let [partitions (.listPartitions datastore)]
     (doseq [partition partitions]
       (partition-delete datastore partition))
     (count partitions))))

(defn partition-new
  "Return a new partition within this session."
  [datastore]
  (.getNewPartition datastore))

;;; =============== Functions for handling data in PSL ====================

(defn pred-append
  "Add ground atoms to PSL from a dataset. The dataset's columns must
  be ordered according to the order of arguments of the predicate.
  See pred-col-ordered-dataset."
  ;; Without a database
  ([datastore write-partition predicate dataset]
   (if (some #{:value} (:column-names dataset))
     ;; Insert with value
     (do
       (assert (= (last (:column-names dataset)) :value))
       (doseq [r (in/to-vect dataset)]
         (.insertValue
          (.getInserter datastore predicate write-partition)
          (last r)                       ; Value
          (to-array (drop-last 1 r))     ; Arguments
          )))
     ;; Insert without value
     (doseq [r (in/to-vect dataset)]
       (.insert
        (.getInserter datastore predicate write-partition)
        (to-array r) ; Arguments
        ))))
  ;; Without a database, in bulk
  ([datastore write-partition predicate dataset temp-bulk-load-path]
   (if (some #{:value} (:column-names dataset))
     ;; Insert with value
     (do
       (assert (= (last (:column-names dataset)) :value))
       (with-open [fout (io/writer temp-bulk-load-path)]
         (csv/write-csv fout (in/to-list dataset) :separator \tab))
       (.loadDelimitedDataTruth
        (.getInserter datastore predicate write-partition)
        temp-bulk-load-path))
     ;; Insert without value
     (do
       (with-open [fout (io/writer temp-bulk-load-path)]
         (csv/write-csv fout (in/to-list dataset) :separator \tab))
       (.loadDelimitedData
        (.getInserter datastore predicate write-partition)
        temp-bulk-load-path))))
  ;; With a database
  ([database predicate dataset]
   (if (some #{:value} (:column-names dataset))
     ;; Insert with value
     (do
       (assert (= (last (:column-names dataset)) :value))
       (doseq [r (in/to-vect dataset)]
         (let [atom (.getAtom
                     database predicate
                     (into-array
                      Constant
                      (for [t (drop-last 1 r)]
                        (UniqueStringID. t))))]
           (.commit database (.setValue atom (last r))))
         ))
     ;; Insert without value
     (doseq [r (in/to-vect dataset)]
       (let [atom (.getAtom
                   database predicate
                   (into-array
                    Constant
                    (for [t r] (UniqueStringID. t))))]
         (.commit database (.setValue atom 1.0)))))))

(defn pred-col-names
  "Get column names for a given predicate as a list of keywords"
  [model pred-name]
  (vec (map keyword (vec (.argNames model pred-name)))))

(defn pred-col-ordered-dataset
  "Reorder columns of a dataset according to the predicate's arguments."
  [dataset model predicate-name]
  (in/sel dataset :cols
          (pred-col-names model predicate-name)))

(defn pred-col-sort-dataset
  "Sort the rows of a dataset by the first argument of the predicate,
  then the second, etc."
  [dataset model predicate-name]
  (in/$order
   (pred-col-names model predicate-name) :asc dataset))

(defn pred-read
  "Read a table from the PSL DB"
  ;; With a supplied database
  ([model db pred-name include-value]
   (let [atoms (Queries/getAllAtoms db (p model pred-name))
         col-ns (pred-col-names model pred-name)]
     (if include-value
       (in/dataset (conj col-ns :value)
                   (for [atom atoms]
                     (flatten [(for [arg (.getArguments atom)] (.getID arg))
                               (.getValue atom)])))
       (in/dataset col-ns
                   (for [atom atoms]
                     (for [arg (.getArguments atom)] (.getID arg)))))))
  ;; Without a database
  ([model datastore parts-read pred-name include-value]
   (let [
         db (open-db
             datastore
             model
             parts-read)
         res (pred-read model db pred-name include-value)]
     (close-db db)
     res)))

(defn round-atoms
  "Round atoms of open predicates using conditional probabilities, per
  Bach et al, 2015 and Goemans and D. P. Williamson., 1994."
  [database model open-predicates]
  (let [mgrs (AtomRegisterGroundRuleStore.)]
    (Grounding/groundAll model (PersistedAtomManager. database) mgrs) 

    ;; Round random variable (open) atoms
    (doseq [rv-pred open-predicates]
      (dosync                  ; These comparisons cannot be done concurrently
       (let
           [;; Transform tvals to [.25,.75]
            atoms
            (for [atom (cu/dbgtim (Queries/getAllAtoms database rv-pred))]
              (let [old-val (.getValue atom)
                    new-val (->> old-val
                                 (* 0.5)
                                 (+ 0.25))]
                (.setValue atom new-val)
                atom))
            ;; Sort by descending truth value: NOT known whether this is good
            atoms (sort (comparator (fn [x y] (> (.getValue x) (.getValue y)))) 
                        atoms)]
         ;; Starting from an arbitrary atom, greedily discretize all
         (doseq [atom atoms]
           (dosync          ; These comparisons cannot be done concurrently
            (let
                [val-old (.getValue atom)  ; Remember old value
                 opts                      ; Discrete options, with scores
                 (for [val-new [0.0 1.0]]  ; Possible discrete values
                   (dosync                 ; Avoid concurrency
                    (.setValue atom val-new) ; Temporarily try value
                    (let
                        [score              ; Score for this value
                         (reduce
                          +
                          (for [gr (.getRegisteredGroundRules mgrs atom)]
                            (GroundRules/getExpectedWeightedLogicalCompatibility
                             gr)))]
                      (.setValue atom val-old) ; Restore old value
                      {:val val-new :score score})))
                 val-best (:val (last (sort-by :score opts)))]
              (.setValue atom val-best)      ; Greedily select a value
              (.commitToDB atom))         ; Change value in DB
            )))))))

(defn round-atoms-simple
  "Round atoms of open predicates, interpreting truth values as
  rounding probabilities."
  [database open-predicates]
  (log/infof "round:: ::starting")
  (doseq [rv-pred open-predicates]
    (let [atoms (Queries/getAllAtoms database rv-pred)]
      (doseq [atom atoms]           
        (let [val-new (if (> (rand) (.getValue atom)) 0 1)]
          (.setValue atom val-new) 
          (dosync (.commitToDB atom)))))))

(defn to-variables "Wrap a list as a list of Variables."
  [args]
  (for [a args]
    (Variable. (name a))))

(defn uid "Return a Unique ID from the provided DataStore. "
  ([string]
   (UniqueStringID. string))
  ([datastore string]
   (log/warn "Unique IDs no longer require data stores")
   (UniqueStringID. string)))
