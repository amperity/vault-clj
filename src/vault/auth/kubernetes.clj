(ns vault.auth.kubernetes
  "The /auth/kubernetes endpoint manages Kubernetes authentication
  functionality.

  Reference: https://www.vaultproject.io/api-docs/auth/kubernetes"
  (:require
    [vault.client.http :as http]
    [vault.client.proto :as proto]
    [vault.util :as u])
  (:import
    vault.client.http.HTTPClient))


(def default-mount
  "Default mount point to use if one is not provided."
  "kubernetes")


(defprotocol API
  "The Kubernetes endpoints manage Kubernetes authentication functionality."

  (with-mount
    [client mount]
    "Return an updated client which will resolve calls against the provided
    mount instead of the default. Passing `nil` will reset the client to the
    default.")

  (login
    [client role jwt]
    "Login to the provided role using a signed JSON Web Token (JWT) for
    authenticating a service account. This method uses the
    `/auth/kubernetes/login` endpoint.

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
    [client role jwt]
    (let [mount (::mount client default-mount)
          api-path (u/join-path "auth" mount "login")]
      (http/call-api
        client :post api-path
        {:info {::mount mount, ::role role}
         :content-type :json
         :body {:jwt jwt :role role}
         :handle-response u/kebabify-body-auth
         :on-success (fn update-auth
                       [auth]
                       (proto/authenticate! client auth))}))))
