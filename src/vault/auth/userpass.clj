(ns vault.auth.userpass
  "The /auth/userpass endpoint manages username & password authentication
  functionality.

  Reference: https://www.vaultproject.io/api-docs/auth/userpass"
  (:require
    [vault.client.http :as http]
    [vault.client.proto :as proto]
    [vault.util :as u])
  (:import
    vault.client.http.HTTPClient))


(defprotocol API
  "The userpass endpoints manage username & password authentication
  functionality."

  (login
    [client username password]
    "Login with the username and password."))


(extend-type HTTPClient

  API

  (login
    [client username password]
    (http/call-api
      client :post (u/join-path "auth/userpass/login/" username)
      {:content-type :json
       :body {:password password}
       :handle-response u/kebabify-body-auth
       :on-success (fn update-auth
                     [auth]
                     (proto/authenticate! client auth))})))
