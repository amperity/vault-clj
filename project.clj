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
  [[org.clojure/clojure "1.11.1"]
   [org.clojure/data.json "2.4.0"]
   [org.clojure/tools.logging "1.2.4"]
   [http-kit "2.7.0-alpha1"]]

  :test-selectors
  {:default (complement :integration)
   :integration :integration}

  :hiera
  {:ignore-ns #{"vault.util"}
   :cluster-depth 2
   :vertical false}

  :profiles
  {:dev
   {:dependencies [[com.amperity/dialog "1.0.1"]]
    :jvm-opts ["-XX:-OmitStackTraceInFastThrow"]}

   :test
   {:jvm-opts ["-Ddialog.profile=test"]}

   :repl
   {:source-paths ["dev"]
    :repl-options {:init-ns vault.repl}
    :dependencies [[org.clojure/tools.namespace "1.3.0"]]
    :jvm-opts ["-Ddialog.profile=repl"]}})
