(defproject amperity/vault-clj "1.0.6"
  :description "Clojure client for the Vault secret management system."
  :url "https://github.com/amperity/vault-clj"
  :license {:name "Apache License"
            :url "http://www.apache.org/licenses/LICENSE-2.0"}

  :deploy-branches ["master"]
  :pedantic? :abort

  :plugins
  [[lein-cloverage "1.2.1"]]

  :dependencies
  [[org.clojure/clojure "1.10.1"]
   [org.clojure/tools.logging "1.1.0"]
   [amperity/envoy "0.3.3"]
   [cheshire "5.10.1"]
   [http-kit "2.5.3"]
   [com.stuartsierra/component "1.0.0"]]

  :test-selectors
  {:default (complement :integration)
   :integration :integration}

  :profiles
  {:dev
   {:dependencies
    [[org.clojure/tools.trace "0.7.11"]
     [ch.qos.logback/logback-classic "1.2.5"]]
    :jvm-opts ["-Dclojure.main.report=stderr"]}

   :repl
   {:source-paths ["dev"]
    :dependencies [[org.clojure/tools.namespace "1.1.0"]]
    :jvm-opts ["-Dvault.log.appender=repl"]}})
