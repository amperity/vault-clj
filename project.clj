(defproject amperity/vault-clj "0.5.2-SNAPSHOT"
  :description "Clojure client for the Vault secret management system."
  :url "https://github.com/amperity/vault-clj"
  :license {:name "Apache License"
            :url "http://www.apache.org/licenses/LICENSE-2.0"}

  :deploy-branches ["master"]
  :pedantic? :abort

  :dependencies
  [[org.clojure/clojure "1.8.0"]
   [org.clojure/tools.logging "0.3.1"]
   [amperity/envoy "0.3.1"]
   [cheshire "5.7.1"]
   [clj-http "2.3.0"]
   [com.stuartsierra/component "0.3.2"]]

  :codox
  {:metadata {:doc/format :markdown}
   :source-uri "https://github.com/amperity/vault-clj/blob/master/{filepath}#L{line}"
   :output-path "target/doc/api"}

  :profiles
  {:dev
   {:dependencies [[commons-logging "1.2"]]}

   :repl
   {:source-paths ["dev"]
    :dependencies [[org.clojure/tools.namespace "0.2.11"]]
    :jvm-opts ["-Dorg.apache.commons.logging.Log=org.apache.commons.logging.impl.SimpleLog"
               "-Dorg.apache.commons.logging.simplelog.showdatetime=true"
               "-Dorg.apache.commons.logging.simplelog.defaultlog=info"
               "-Dorg.apache.commons.logging.simplelog.log.vault=debug"]}

   :test
   {:plugins [[lein-cloverage "1.0.9"]]
    :jvm-opts ["-Dorg.apache.commons.logging.Log=org.apache.commons.logging.impl.NoOpLog"]}})
