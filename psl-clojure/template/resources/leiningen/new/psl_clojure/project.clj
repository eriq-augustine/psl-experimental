(defproject {{raw-name}} "0.1.0-SNAPSHOT"
  :description "FIXME: write description"
  :url "http://example.com/FIXME"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.8.0"]
                 [org.linqs/psl-clojure "2.1.0-SNAPSHOT"]]
  :main ^:skip-aot {{namespace}}
  ;; :aot :all
  ;; :pedantic :warn
  :target-path "target/%s"
  :profiles {:uberjar {:aot :all}}
  ) 
