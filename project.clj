(defproject amperity/vault-clj "1.0.5-SNAPSHOT"
  :description "Clojure client for the Vault secret management system."
  :url "https://github.com/amperity/vault-clj"
  :license {:name "Apache License"
            :url "http://www.apache.org/licenses/LICENSE-2.0"}

  :deploy-branches ["master"]
  :pedantic? :abort

  :dependencies
  [[org.clojure/clojure "1.10.1"]
   [org.clojure/tools.logging "1.1.0"]
   [amperity/envoy "0.3.3"]
   [cheshire "5.10.0"]
   [http-kit "2.5.3"]
   [com.stuartsierra/component "1.0.0"]]

  :profiles
  {:dev
   {:dependencies [[commons-logging "1.2"]
                   [org.clojure/tools.trace "0.7.11"]
                   [org.slf4j/slf4j-simple "1.7.30"]]}

   :repl
   {:source-paths ["dev"]
    :dependencies [[org.clojure/tools.namespace "1.1.0"]]
    :jvm-opts ["-Dorg.apache.commons.logging.Log=org.apache.commons.logging.impl.SimpleLog"
               "-Dorg.apache.commons.logging.simplelog.showdatetime=true"
               "-Dorg.apache.commons.logging.simplelog.defaultlog=info"
               "-Dorg.apache.commons.logging.simplelog.log.vault=debug"
               "-Dclojure.main.report=stderr"]}

   :test
   {:plugins [[lein-cloverage "1.2.1"]]
    :jvm-opts ["-Dorg.apache.commons.logging.Log=org.apache.commons.logging.impl.NoOpLog"
               "-Dclojure.main.report=stderr"]}})
