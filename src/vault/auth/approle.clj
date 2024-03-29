(ns vault.auth.approle
  "The `/auth/approle` endpoint manages approle role-id & secret-id authentication functionality.

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
  "The approle auth endpoints manage role_id and secret_id authentication."

  (with-mount
    [client mount]
    "Return an updated client which will resolve calls against the provided
    mount instead of the default. Passing `nil` will reset the client to the
    default.")

  (configure-role!
    [client role-name opts]
    "Create a new role or update an existing role. At least one option must be
    specified. This method uses the `/auth/approle/role/:role_name` endpoint.

    Options:

    - `:bind-secret-id` (boolean)

      If a `secret-id` is required to be presented when logging in with this
      role.

    - `:secret-id-bound-cidrs` (collection)

      Collection of CIDR blocks. When set, specifies blocks of IP addresses
      which can perform the login operation.

    - `:secret-id-num-uses` (integer)

      The number of times any single `secret-id` can be used to fetch a token
      from this approle, after which the `secret-id` will expire. Specify `0` for
      unlimited uses.

    - `:secret-id-ttl` (string)

      Duration in either an integer number of seconds (`3600`) or a string time
      unit (`60m`) after which any `secret-id` expires.

    - `:local-secret-ids` (boolean)

      If set, the secret IDs generated using this role will be cluster local.
      This can only be set during role creation and once set, it can't be reset
      later.

    - `:token-ttl` (integer or string)

      The incremental lifetime for generated tokens.

    - `:token-max-ttl` (integer or string)

      The maximum lifetime for generated tokens.

    - `:token-policies` (collection)

      List of policies to encode onto generated tokens.

    - `:token-bound-cidrs` (collection)

      List of CIDR blocks; if set, specifies blocks of IP addresses which can
      authenticate successfully.

    - `:token-explicit-max-ttl` (integer or string)

      If set, will encode an explicit hard cap for token life.

    - `:token-no-default-policy` (boolean)

      If set, the default policy will not be set on generated tokens, otherwise
      it will be added to the policies set in `:token-policies`.

    - `:token-num-uses` (integer)

      The maximum amount of times a generated token may be used. Specify `0`
      for unlimited uses.

    - `:token-period` (integer or string)

      The period to set on a token.

    - `:token-type` (string)

      The type of token that should be generated.")

  (list-roles
    [client]
    "Return a list of the existing roles. This method uses the
    `/auth/approle/role` endpoint.")

  (read-role
    [client role-name]
    "Read the properities associated with an approle. This method uses the
    `/auth/approle/role/:role_name` endpoint.")

  (read-role-id
    [client role-name]
    "Read the `role-id` of an exiting role. This method uses the
    `/auth/approle/role/:role_name/role-id` endpont.")

  (generate-secret-id!
    [client role-name]
    [client role-name opts]
    "Generate a new `secret-id` for an existing role. This method uses the
    `/auth/approle/role/:role_name/secret-id` endpoint.

    Options:

    - `:metadata` (string)

      Metadata tied to the `secret-id`. This should be a JSON-formatted string
      containing key-value pairs. This metadata is logged in audit logs in plaintext.

    - `:cidr-list` (collection)

      Collection of CIDR blocks enforcing `secret-ids` to be used from specific IP addresses.

    - `:token-bound-cidrs` (collection)

      Collection of CIDR blocks; when set, specifies blocks of IP addresses that can use
      auth tokens generated by the `secret-id`.")

  (login
    [client role-id secret-id]
    "Login using an approle `role-id` and `secret-id`. This method uses the
    `/auth/approle/login` endpoint.

    Returns the `auth` map from the login endpoint and updates the auth
    information in the client, including the new client token."))


(extend-type HTTPClient

  API

  (with-mount
    [client mount]
    (if (some? mount)
      (assoc client ::mount mount)
      (dissoc client ::mount)))


  (configure-role!
    [client role-name opts]
    (let [mount (::mount client default-mount)
          api-path (u/join-path "auth" mount "role" role-name)]
      (http/call-api
        client ::configure-role!
        :post api-path
        {:info {::mount mount, ::role role-name}
         :content-type :json
         :body (-> opts
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
                   (u/snakify-keys))})))


  (list-roles
    [client]
    (let [mount (::mount client default-mount)
          api-path (u/join-path "auth" mount "role")]
      (http/call-api
        client ::list-roles
        :list api-path
        {:info {::mount mount}
         :handle-response u/kebabify-body-data})))


  (read-role
    [client role-name]
    (let [mount (::mount client default-mount)
          api-path (u/join-path "auth" mount "role" role-name)]
      (http/call-api
        client ::read-role
        :get api-path
        {:info {::mount mount, ::role role-name}
         :handle-response u/kebabify-body-data})))


  (read-role-id
    [client role-name]
    (let [mount (::mount client default-mount)
          api-path (u/join-path "auth" mount "role" role-name "role-id")]
      (http/call-api
        client ::read-role-id
        :get api-path
        {:info {::mount mount, ::role role-name}
         :handle-response u/kebabify-body-data})))


  (generate-secret-id!
    ([client role-name]
     (generate-secret-id! client role-name {}))
    ([client role-name opts]
     (let [mount (::mount client default-mount)
           api-path (u/join-path "auth" mount "role" role-name "secret-id")]
       (http/call-api
         client ::generate-secret-id!
         :post api-path
         {:info {::mount mount, ::role role-name}
          :content-type :json
          :body (-> opts
                    (select-keys [:metadata
                                  :cidr-list
                                  :token-bound-cidrs])
                    (u/snakify-keys))
          :handle-response u/kebabify-body-data}))))


  (login
    [client role-id secret-id]
    (let [mount (::mount client default-mount)
          api-path (u/join-path "auth" mount "login")]
      (http/call-api
        client ::login
        :post api-path
        {:info {::mount mount}
         :content-type :json
         :body {:role_id role-id
                :secret_id secret-id}
         :handle-response u/kebabify-body-auth
         :on-success (fn update-auth
                       [auth]
                       (proto/authenticate! client auth))}))))
