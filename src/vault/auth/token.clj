(ns vault.auth.token
  "The /auth/token endpoint manages token-based authentication functionality.

  Reference: https://www.vaultproject.io/api-docs/auth/token"
  (:require
    [vault.client.flow :as f]
    [vault.client.http :as http]
    [vault.client.mock :as mock]
    [vault.client.proto :as proto]
    [vault.util :as u])
  (:import
    vault.client.http.HTTPClient
    vault.client.mock.MockClient))


;; ## API Protocol

(defprotocol API
  "The token auth endpoints manage token authentication functionality."

  (create-token!
    [client params]
    "Create a new auth token. The token will be a child of the current one
    unless the `:no-parent` option is true. This method uses the
    `/auth/token/create` endpoint.

    For parameter options, see:
    https://www.vaultproject.io/api-docs/auth/token#create-token")

  (create-orphan-token!
    [client params]
    "Create a new auth token with no parent. This method uses the
    `/auth/token/create-orphan` endpoint.

    Parameters are the same as for the `create-token!` call.")

  (create-role-token!
    [client role-name params]
    "Create a new auth token in the named role. This method uses the
    `/auth/token/create/:role-name` endpoint.

    Parameters are the same as for the `create-token!` call.")

  (lookup-token
    [client params]
    "Look up auth information about a token.

    Depending on the parameters, this method operates on:
    - a directly-provided `:token`
    - a token `:accessor`
    - otherwise, the currently-authenticated token")

  (renew-token!
    [client params]
    "Renew a lease associated with a token. Token renewal is possible only if
    there is a lease associated with it.

    Depending on the parameters, this method operates on:
    - a directly-provided `:token`
    - a token `:accessor`
    - otherwise, the currently-authenticated token

    The parameters may also include a requested `:increment` value.")

  (revoke-token!
    [client params]
    "Revoke a token and all child tokens. When the token is revoked, all
    dynamic secrets generated with it are also revoked. Returns nil.

    Depending on the parameters, this method operates on:
    - a directly-provided `:token`
    - a token `:accessor`
    - otherwise, the currently-authenticated token"))


(defn resolve-auth!
  "Look up the currently-authenticated token, merging updated information into
  the client's auth info. Returns the updated auth data."
  [client]
  (let [auth-info (f/call-sync lookup-token client {})]
    (proto/authenticate! client auth-info)
    (proto/auth-info client)))


;; ## Mock Client

(extend-type MockClient

  API

  (lookup-token
    [client params]
    (let [root-token "r00t"
          root-accessor "TbmQ9IWujYqaUCuQQ2vm3uUY"]
      (if (or (= root-token (:token params))
              (= root-accessor (:accessor params))
              (and (nil? (:token params))
                   (nil? (:accessor params))))
        (mock/success-response
          client
          {:id (if (:accessor params)
                 ""
                 root-token)
           :accessor root-accessor
           :creation-time 1630768626
           :creation-ttl 0
           :display-name "token"
           :entity-id ""
           :expire-time nil
           :explicit-max-ttl 0
           :issue-time "2021-09-04T08:17:06-07:00"
           :meta nil
           :num-uses 0
           :orphan true
           :path "auth/token/create"
           :policies ["root"]
           :renewable false
           :ttl 0
           :type "service"})
        (mock/error-response
          client
          (ex-info "Vault API errors: bad token"
                   {:vault.client/errors ["bad token"]
                    :vault.client/status 403})))))

  ,,,)


;; ## HTTP Client

(extend-type HTTPClient

  API

  (create-token!
    [client params]
    (http/call-api
      client :post "auth/token/create"
      {:content-type :json
       :body (u/snakify-keys params)
       :handle-response u/kebabify-body-auth}))


  (create-orphan-token!
    [client params]
    (http/call-api
      client :post "auth/token/create-orphan"
      {:content-type :json
       :body (u/snakify-keys params)
       :handle-response u/kebabify-body-auth}))


  (create-role-token!
    [client role-name params]
    (http/call-api
      client :post (u/join-path "auth/token/create" role-name)
      {:content-type :json
       :body (u/snakify-keys params)
       :handle-response u/kebabify-body-auth}))


  (lookup-token
    [client params]
    (cond
      (:token params)
      (http/call-api
        client :post "auth/token/lookup"
        {:content-type :json
         :body {:token (:token params)}
         :handle-response u/kebabify-body-data})

      (:accessor params)
      (http/call-api
        client :post "auth/token/lookup-accessor"
        {:content-type :json
         :body {:accessor (:accessor params)}
         :handle-response u/kebabify-body-data})

      :else
      (http/call-api
        client :get "auth/token/lookup-self"
        {:handle-response u/kebabify-body-data})))


  (renew-token!
    [client params]
    (cond
      (:token params)
      (http/call-api
        client :post "auth/token/renew"
        {:content-type :json
         :body (select-keys params [:token :increment])
         :handle-response u/kebabify-body-auth})

      (:accessor params)
      (http/call-api
        client :post "auth/token/renew-accessor"
        {:content-type :json
         :body (select-keys params [:accessor :increment])
         :handle-response u/kebabify-body-auth})

      :else
      (http/call-api
        client :post "auth/token/renew-self"
        {:content-type :json
         :body (select-keys params [:increment])
         :handle-response u/kebabify-body-auth
         :on-success (fn update-auth
                       [auth]
                       (proto/authenticate! client auth))})))


  (revoke-token!
    [client params]
    (cond
      (:token params)
      (http/call-api
        client :post "auth/token/revoke"
        {:content-type :json
         :body {:token (:token params)}})

      (:accessor params)
      (http/call-api
        client :post "auth/token/revoke-accessor"
        {:content-type :json
         :body {:accessor (:accessor params)}})

      :else
      (http/call-api
        client :post "auth/token/revoke-self"
        {}))))
