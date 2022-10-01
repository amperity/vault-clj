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
    [client params]
    "Login to the provided role using a JWT. This method uses the
    `/auth/kubernetes/login` endpoint.

    Parameters must include:
    - `:role`
      Name of the role against which the login is being attempted.
    - `:jwt`
      Signed JSON Web Token (JWT) for authenticating a service account.

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
    [client {:keys [jwt role]}]
    (when-not jwt
      (throw (IllegalArgumentException. "Kubernetes auth params must include :jwt")))
    (when-not role
      (throw (IllegalArgumentException. "Kubernetes auth params must include :role")))
    (let [mount (::mount client default-mount)
          api-path (u/join-path "auth" mount "login")]
      (http/call-api
        client :post api-path
        {:content-type :json
         :body {:jwt jwt :role role}
         :handle-response u/kebabify-body-auth
         :on-success (fn update-auth
                       [auth]
                       (proto/authenticate! client auth))}))))
