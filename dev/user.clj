(ns user
  (:require
    [clojure.java.io :as io]
    [clojure.repl :refer :all]
    [clojure.string :as str]
    [clojure.tools.namespace.repl :refer [refresh]]
    [clojure.tools.trace :as trace]
    [com.stuartsierra.component :as component]
    [org.httpkit.client]
    [vault.client.http]
    [vault.client.mock]
    [vault.core :as vault]
    [vault.env :as venv]
    [vault.lease :as lease]
    [vault.secrets.kvv2 :as kvv2]))


(def client nil)


(defn stop-client
  []
  (when client
    (alter-var-root #'client component/stop)))


(defn reset-client
  []
  (stop-client)
  (let [vault-addr (or (System/getenv "VAULT_ADDR") "http://localhost:8200")
        vault-token (System/getenv "VAULT_TOKEN")
        vault-client (assoc (vault/new-client vault-addr)
                            :lease-renewal-window 600
                            :lease-check-period    60
                            :lease-check-jitter    20)]
    (when vault-token
      (vault/authenticate! vault-client :token vault-token))
    (alter-var-root #'client (constantly (component/start vault-client)))))
