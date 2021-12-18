(defproject com.amperity/vault-clj "2.0.0-SNAPSHOT"
  :description "Clojure client for the Vault secret management system."
  :url "https://github.com/amperity/vault-clj"
  :license {:name "Apache License"
            :url "http://www.apache.org/licenses/LICENSE-2.0"}

  :deploy-branches ["master"]
  :pedantic? :abort

  :plugins
  [[lein-cloverage "1.2.1"]]

  :dependencies
  [[org.clojure/clojure "1.10.3"]
   [org.clojure/data.json "2.4.0"]
   [org.clojure/tools.logging "1.1.0"]
   [com.stuartsierra/component "1.0.0"]
   [http-kit "2.5.3"]]

  :test-selectors
  {:default (complement :integration)
   :integration :integration}

  :hiera
  {:cluster-depth 2
   :vertical false}

  :profiles
  {:dev
   {:dependencies
    [[ch.qos.logback/logback-classic "1.2.5"]]
    :jvm-opts ["-Dclojure.main.report=stderr"]}

   :repl
   {:source-paths ["dev"]
    :repl-options {:init-ns vault.repl}
    :dependencies [[org.clojure/tools.namespace "1.1.0"]]
    :jvm-opts ["-XX:-OmitStackTraceInFastThrow"
               "-Dvault.log.appender=repl"]}})
