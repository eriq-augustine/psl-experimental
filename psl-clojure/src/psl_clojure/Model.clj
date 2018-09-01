(ns psl-clojure.Model
  (:gen-class
   :extends org.linqs.psl.groovy.PSLModel
   :state state
   :methods [[argNames [String] clojure.lang.PersistentVector]]
   :exposes-methods {add parentAdd}
   :init init)
  (:require [clj-util.core :as u]))

(defn -init
  [obj data-store]
  [[obj data-store] (atom {:preds {}})])

(defn -add
  "Add a predicate to the model."
  [this args-map]
  (let [pred-name (get args-map "predicate")
        arg-names (get args-map "names")]
    (swap! (.state this) assoc-in [:preds pred-name] {:arg-names arg-names})
    (.parentAdd this args-map)))

(defn -argNames
  "List names of arguments of predicate with given name."
  [this pred-name]
  (let [arg-names (get-in @(.state this) [:preds pred-name :arg-names])]
    arg-names))
