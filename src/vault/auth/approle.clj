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

  (upsert-role
    [client role-id opts]
    "Creates a new AppRole or updates an existing AppRole.

    When creating or updating an AppRole, there must be at least
    one option supplied
    
    For additional information see https://www.vaultproject.io/api-docs/auth/approle#create-update-approle

    Options:
    - `:bind-secret-id` (boolean)
      If a `secret-id` is required to be presented when logging in with this role
    - `:secret-id-bound-cidrs` (collection)
      Collection of CIDR blocks. When set, specifies blocks of IP addresses
      which can perform the login operation.
    - `:secret-id-num-uses` (integer)
      The number of times any single `secret-id` can be used to fetch a token
      from this AppRole, after which the `secret-id` will expire. Specify 0 for unlimited uses.
    - `:secret-id-ttl` (string)'
      Duration in either an integer number of seconds (3600) or an integer time unit (60m)
      after which any `secret-id` expires.
    - `:local-secret-ids` (boolean)
      If set, the secret IDs generated using this role will be cluster local.
      This can only be set during role creation and once set, it can't be reset later.
    - `:token-ttl` (integer or string)
      The incremental lifetime for generated tokens.
    - `:token-max-ttl` (integer or string)
      The maximum lifetime for generated tokens. 
    - `:token-policies` (collection or comma-delimited string)
      List of policies to encode onto generated tokens.
    - `:token-bound-cidrs` (collection or comma-delimited string)
      List of CIDR blocks; if set, specifies blocks of IP addresses
      which can authenticate successfully.
    - `:token-explicit-max-ttl` (integer or string)
      If set, will encode an explicit hard cap for token life.
    - `:token-no-default-policy` (boolean)
      If set, the default policy will not be set on generated tokens, otherwise it
      will be added to the policies set in `:token-policies`.
    - `:token-num-uses` (integer)
      The maximum amount of times a generated token may be used. Specify 0 for unlimited uses.
    - `:token-period` (integer or string)
      The period to set on a token.
    - `:token-type` (string)
      The type of token that should be generated.")

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

  (upsert-role
    [client role-id opts]
    (let [mount (::mount client default-mount)
          api-path (u/join-path "auth" mount "role" role-id)]
      (http/call-api
        client :post api-path
        {:content-type :json
         :body-type (-> opts
                        (select-keys [:bind-secret-id
                                      :secret-id-bound-cidrs
                                      :secret-id-num-uses
                                      :secret-id-ttl
                                      :local-secret-ids
                                      :token-ttl
                                      :token-max-ttl
                                      :token-policies
                                      :token-bound-cidrs
                                      :token-explicit-max-ttl
                                      :token-no-default-policy
                                      :token-num-uses
                                      :token-period
                                      :token-type])
                        (u/snakify-keys)
                        (u/stringify-keys))})))


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
