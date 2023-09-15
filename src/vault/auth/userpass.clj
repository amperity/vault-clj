(ns vault.auth.userpass
  "The `/auth/userpass` endpoint manages username & password authentication
  functionality.

  Reference: https://www.vaultproject.io/api-docs/auth/userpass"
  (:require
    [vault.client.http :as http]
    [vault.client.proto :as proto]
    [vault.util :as u])
  (:import
    vault.client.http.HTTPClient))


(def default-mount
  "Default mount point to use if one is not provided."
  "userpass")


(defprotocol API
  "The userpass endpoints manage username & password authentication
  functionality."

  (with-mount
    [client mount]
    "Return an updated client which will resolve calls against the provided
    mount instead of the default. Passing `nil` will reset the client to the
    default.")

  (login
    [client username password]
    "Login with the username and password. This method uses the
    `/auth/userpass/login/:username` endpoint.

    Returns the `auth` map from the login endpoint and updates the auth
    information in the client, including the new client token."))


(extend-type HTTPClient

  API

  (with-mount
    [client mount]
    (if (some? mount)
      (assoc client ::mount mount)
      (dissoc client ::mount)))


  (login
    [client username password]
    (let [mount (::mount client default-mount)
          api-path (u/join-path "auth" mount "login" username)]
      (http/call-api
        client :post api-path
        {:info {::mount mount, ::username username}
         :content-type :json
         :body {:password password}
         :handle-response u/kebabify-body-auth
         :on-success (fn update-auth
                       [auth]
                       (proto/authenticate! client auth))}))))
