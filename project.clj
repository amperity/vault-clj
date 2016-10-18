(defproject amperity/vault-clj "0.4.0-SNAPSHOT"
  :description "Clojure client for the Vault secret management system."
  :url "https://github.com/amperity/vault-clj"
  :license {:name "Apache License"
            :url "http://www.apache.org/licenses/LICENSE-2.0"}

  :deploy-branches ["master"]

  :dependencies
  [[org.clojure/clojure "1.8.0"]
   [org.clojure/tools.logging "0.3.1"]
   [cheshire "5.6.3"]
   [clj-http "3.3.0"]])
