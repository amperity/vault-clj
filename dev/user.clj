(ns user
  (:require
    [clojure.java.io :as io]
    [clojure.repl :refer :all]
    [clojure.string :as str]
    [clojure.tools.namespace.repl :refer [refresh]]
    [com.stuartsierra.component :as component]
    [vault.core :as vault]
    (vault.client http mock)
    [vault.env :as venv]
    [vault.lease :as lease]))


(def client nil)


(defn stop-client
  []
  (when client
    (alter-var-root #'client component/stop)))


(defn reset-client
  []
  (when client
    (alter-var-root #'client component/stop))
  (let [vault-addr (or (System/getenv "VAULT_ADDR") "http://localhost:8200")
        vault-token (System/getenv "VAULT_TOKEN")
        vault-client (assoc (vault/new-client vault-addr)
                            :lease-renewal-window 600
                            :lease-check-period    60
                            :lease-check-jitter    20)]
    (when vault-token
      (vault/authenticate! vault-client :token vault-token))
    (alter-var-root #'client (constantly (component/start vault-client)))))
