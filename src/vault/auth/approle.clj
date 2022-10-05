(ns vault.auth.approle
  "The /auth/approle endpoint manages approle role-id & secret-id authentication functionality.

  Reference: https://www.vaultproject.io/api-docs/auth/approle"
  (:require
    [vault.client.http :as http]
    [vault.client.proto :as proto]
    [vault.util :as u])
  (:import
    vault.client.http.HTTPClient))


(def default-mount
  "Default mount point to use if one is not provided."
  "approle")


(defprotocol API
  "The AppRole auth endpoints manage role_id and secret_id authentication"

  (with-mount
    [client mount]
    "Return an updated client which will resolve calls against the provided
    mount instead of the default. Passing `nil` will reset the client to the
    default.")

  (login
    [client role-id secret-id]
    "Login using an AppRole role_id and secret_id.
    This method uses the `/auth/approle/login` endpoint.

    Returns the `auth` map from the login endpoint and also updates the auth
    information in the client, including the new client token."))


(extend-type HTTPClient

  API

  (with-mount
    [client mount]
    (if (some? mount)
      (assoc client ::mount mount)
      (dissoc client ::mount)))


  (login
    [client role-id secret-id]
    (let [mount (::mount client default-mount)
          api-path (u/join-path "auth" mount "login")]
      (http/call-api
        client :post api-path
        {:content-type :json
         :body {:role_id role-id
                :secret_id secret-id}
         :handle-response u/kebabify-body-auth
         :on-success (fn update-auth
                       [auth]
                       (proto/authenticate! client auth))}))))
