(ns vault.repl
  (:require
    [clojure.java.io :as io]
    [clojure.repl :refer :all]
    [clojure.stacktrace :refer [print-cause-trace]]
    [clojure.string :as str]
    [clojure.tools.namespace.repl :refer [refresh]]
    [vault.auth :as auth]
    [vault.auth.approle :as auth.approle]
    [vault.auth.token :as auth.token]
    [vault.auth.userpass :as auth.userpass]
    [vault.client :as vault]
    [vault.client.flow :as f]
    [vault.client.http :as http]
    [vault.client.mock :as mock]
    [vault.lease :as lease]
    [vault.secret.aws :as aws]
    [vault.secret.database :as database]
    [vault.secret.kv.v1 :as kv1]
    [vault.secret.kv.v2 :as kv2]
    [vault.sys.auth :as sys.auth]
    [vault.sys.health :as sys.health]
    [vault.util :as u]))


(def client nil)


(defn stop-client
  "Stop the running client, if any."
  []
  (when client
    (alter-var-root #'client vault/stop)))


(defn init-client
  "Initialize a new client, stopping the running one if present."
  []
  (stop-client)
  (let [vault-addr "http://127.0.0.1:8200"
        vault-token "t0p-53cr3t"
        vault-client (http/http-client vault-addr)]
    (when vault-token
      (vault/authenticate! vault-client vault-token))
    (alter-var-root #'client (constantly (vault/start vault-client))))
  :init)


(defn reset
  "Reload any changed code, and initialize a new client."
  []
  (stop-client)
  (refresh :after 'vault.repl/init-client))
