(ns vault.secrets.kv.v1
  "The kv secrets engine is used to store arbitrary secrets within the
  configured physical storage for Vault. Writing to a key in the kv-v1 backend
  will replace the old value; sub-fields are not merged together.

  Reference: https://www.vaultproject.io/api-docs/secret/kv/kv-v1"
  (:require
    [clojure.string :as str]
    [vault.client.http :as http]
    [vault.client.mock :as mock]
    [vault.client.util :as u])
  (:import
    vault.client.http.HTTPClient
    vault.client.mock.MockClient))


(def default-mount
  "Default mount point to use if one is not provided."
  "secrets")


;; ## API Protocol

;; TODO: how to best express mount customization?
(defprotocol API
  "..."

  (list-secrets
    [client path]
    [client mount path]
    "Returns a vector of the secret names located under a path.

    Options:
    ...")

  (read-secret
    [client path opts]
    [client mount path opts]
    "...")

  (write-secret!
    [client path data]
    [client mount path data]
    "...")

  (delete-secret!
    [client path]
    [client mount path]
    "..."))


;; ## Mock Client

(extend-type MockClient

  API

  ,,,)


;; ## HTTP Client

(extend-type HTTPClient

  API

  ,,,)
