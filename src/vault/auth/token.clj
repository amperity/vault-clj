(ns vault.auth.token
  "The /auth/token endpoint manages token-based authentication functionality.

  Reference: https://www.vaultproject.io/api-docs/auth/token"
  (:require
    [clojure.string :as str]
    [vault.client.http :as http]
    [vault.client.mock :as mock]
    [vault.client.util :as u])
  (:import
    vault.client.http.HTTPClient
    vault.client.mock.MockClient))


;; ## API Protocol

(defprotocol API
  "The token auth endpoints manage token authentication functionality."

  ,,,)


;; ## Mock Client

(extend-type MockClient

  API

  ,,,)


;; ## HTTP Client

(extend-type HTTPClient

  API

  ,,,)
