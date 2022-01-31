(defproject amperity/vault-clj "1.1.1"
  :description "Clojure client for the Vault secret management system."
  :url "https://github.com/amperity/vault-clj"
  :license {:name "Apache License"
            :url "http://www.apache.org/licenses/LICENSE-2.0"}

  :deploy-branches ["master"]
  :pedantic? :abort

  :plugins
  [[lein-cloverage "1.2.2"]]

  :dependencies
  [[org.clojure/clojure "1.10.3"]
   [org.clojure/tools.logging "1.2.3"]
   [amperity/envoy "1.0.0"]
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
     [ch.qos.logback/logback-classic "1.2.10"]]
    :jvm-opts ["-Dclojure.main.report=stderr"]}

   :repl
   {:source-paths ["dev"]
    :dependencies [[org.clojure/tools.namespace "1.2.0"]]
    :jvm-opts ["-Dvault.log.appender=repl"]}})
