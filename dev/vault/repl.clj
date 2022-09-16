(ns vault.repl
  (:require
    [clojure.java.io :as io]
    [clojure.repl :refer :all]
    [clojure.stacktrace :refer [print-cause-trace]]
    [clojure.string :as str]
    [clojure.tools.namespace.repl :refer [refresh]]
    [vault.auth.token :as auth.token]
    [vault.client :as vault]
    [vault.client.handler :as h]
    [vault.client.http :as http]
    [vault.client.mock :as mock]
    [vault.component :as component]
    [vault.lease :as lease]
    [vault.secret.database :as database]
    [vault.secret.kv.v1 :as kv1]
    [vault.sys.auth :as sys.auth]
    [vault.sys.health :as sys.health]
    [vault.util :as u]))


(def client nil)


(defn stop-client
  "Stop the running client, if any."
  []
  (when client
    (alter-var-root #'client component/stop)))


(defn init-client
  "Initialize a new client, stopping the running one if present."
  []
  (stop-client)
  (let [vault-addr "http://127.0.0.1:8200"
        vault-token "t0p-53cr3t"
        vault-client (vault/new-client vault-addr)]
    (when vault-token
      (vault/authenticate! vault-client {:client-token vault-token}))
    (alter-var-root #'client (constantly (component/start vault-client))))
  :init)


(defn reset
  "Reload any changed code, and initialize a new client."
  []
  (stop-client)
  (refresh :after 'vault.repl/init-client))
