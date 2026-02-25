(ns vault.auth.azure
  (:require
    [clojure.data.json :as json]
    [vault.client.http :as http]
    [vault.client.proto :as proto]
    [vault.util :as u])
  (:import
    vault.client.http.HTTPClient))


(def default-mount
  "Default mount point for the auth method."
  "azure")


(defprotocol API
  "Vault Azure auth method"

  (login
    [client params]
    "Logs in to Vault using Azure credentials,
    as per <https://developer.hashicorp.com/vault/docs/auth/azure>.

    The `params` map is transformed into snake keys and used as the
    request body to the login endpoint. See <https://developer.hashicorp.com/vault/api-docs/auth/azure#login>
    for all supported parameters.

    Common parameters are:
    - `:role` - Vault role name
    - `:jwt` - Azure access token
    - `:subscription-id` - Azure subscription ID
    - `:resource-group-name` - Azure resource group name
    - `:vm-name` - When using an Azure VM system-assigned identity or managed identity, the name of the Azure VM")

  (with-mount
    [client mount]
    "Configures the mount point of the auth method, if it is not the default.

    Returns a new client. If `mount` is nil, returns a client with the default
    mount point."))


(extend-type HTTPClient

  API

  (login
    [client {:keys [role jwt] :as params}]
    (let [mount (::mount client default-mount)
          api-path (u/join-path "auth" mount "login")
          _ (prn params)
          body (u/snakify-keys params)]
      (http/call-api
        client ::login
        :post api-path
        {:info {::mount mount}
         :content-type :json
         :body body
         :handle-response u/kebabify-body-auth
         :on-success (fn update-auth
                       [auth]
                       (proto/authenticate! client auth))})))

  (with-mount
    [client mount]
    (if (some? mount)
      (assoc client ::mount mount)
      (dissoc client ::mount))))
