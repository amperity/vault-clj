(ns vault.auth.github
  "The /auth/github endpoint manages GitHub authentication functionality.

  Reference: https://www.vaultproject.io/api-docs/auth/github"
  (:require
    [vault.client.http :as http]
    [vault.client.proto :as proto]
    [vault.util :as u])
  (:import
    vault.client.http.HTTPClient))


(def default-mount
  "Default mount point to use if one is not provided."
  "github")


(defprotocol API
  "The GitHub auth endpoints manage GitHub authentication functionality."

  (with-mount
    [client mount]
    "Return an updated client which will resolve calls against the provided
    mount instead of the default. Passing `nil` will reset the client to the
    default.")

  (login
    [client token]
    "Login using a GitHub access token. This method uses the
    `/auth/github/login` endpoint.

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
    [client token]
    (let [mount (::mount client default-mount)
          api-path (u/join-path "auth" mount "login")]
      (http/call-api
        client :post api-path
        {:info {::mount mount}
         :content-type :json
         :body {:token token}
         :handle-response u/kebabify-body-auth
         :on-success (fn update-auth
                       [auth]
                       (proto/authenticate! client auth))}))))
