(ns leiningen.new.psl-clojure
  "Generate a basic PSL Clojure project."
  (:require [leiningen.new.templates :refer [renderer project-name name-to-path ->files multi-segment sanitize-ns]]
            [leiningen.core.main :as main]))

(def render (renderer "psl-clojure"))

(defn psl-clojure
  "A PSL Clojure project template."
  [name]
  (let [main-ns (multi-segment (sanitize-ns name))
        data {:raw-name name
              :name (project-name name)
              :namespace main-ns
              :sanitized (name-to-path name)}]
    (main/info "Generating fresh 'lein new' psl-clojure project.")
    (->files data
             ["project.clj" (render "project.clj" data)]
             ["resources/psl.properties" (render "psl.properties" data)]
             ["resources/log4j.properties" (render "log4j.properties" data)]
             ["src/{{sanitized}}/core.clj" (render "core.clj" data)])))
