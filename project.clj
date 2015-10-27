(defproject counsyl/vault-clj "0.1.0-SNAPSHOT"
  :description "Clojure client for the Vault secret management system."
  :url "https://github.com/counsyl/vault-clj"
  :license {:name "Apache License"
            :url "http://www.apache.org/licenses/LICENSE-2.0"}

  :deploy-branches ["master"]

  :dependencies
  [[clj-http "2.0.0"]
   [org.clojure/clojure "1.7.0"]
   [org.clojure/tools.logging "0.3.1"]])
