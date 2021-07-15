(ns user
  (:require
    [clojure.java.io :as io]
    [clojure.repl :refer :all]
    [clojure.string :as str]
    [clojure.tools.namespace.repl :refer [refresh]]
    [clojure.tools.trace :as trace]
    [com.stuartsierra.component :as component]
    [org.httpkit.client]
    [vault.client.api-util]
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


(comment

  (trace/trace-ns vault.client.http)
  (trace/trace-ns vault.core)
  (trace/trace-ns vault.client.api-util)
  (trace/trace-ns vault.secrets.kvv2)
  (trace/trace-ns org.httpkit.client)

  ;; export VAULT_TOKEN="Token value"
  (let [client (vault/new-client (System/getenv "VAULT_ADDR"))]
    (vault/authenticate! client :token (System/getenv "VAULT_TOKEN"))
    (kvv2/read-secret client "DocSearch" "stage/app"))

  (System/getenv "VAULT_TOKEN")

  0
  )
