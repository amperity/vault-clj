(ns vault.secret.database
  "The database secrets engine is used to manage dynamically-issued credentials
  for users of a database backend such as mysql, postgresql, mongodb, etc. The
  vault server uses a priveleged 'root' user to create new users with randomized
  passwords on-demand for callers.

  Reference: https://www.vaultproject.io/api-docs/secret/databases"
  (:require
    [vault.client.http :as http]
    [vault.client.mock :as mock]
    [vault.secret.common :as comm]
    [vault.lease :as lease]
    [vault.util :as u])
  (:import
    vault.client.http.HTTPClient
    vault.client.mock.MockClient))


(def default-mount
  "Default mount point to use if one is not provided."
  "database")


;; ## API Protocol

(defprotocol API
  "The database secrets engine is used to manage dynamic users in a backing
  database system."

  (with-mount
    [client mount]
    "Return an updated client which will resolve calls against the provided
    mount instead of the default. Passing `nil` will reset the client to the
    default.")

  (generate-credentials!
    [client role-name opts]
    "Generate a new set of dynamic credentials based on the named role.

    Options:
    - `:renew?`
      If true, attempt to automatically renew the credentials lease when near
      expiry. (Default: false)
    - `:renew-within`
      Renew the secret when within this many seconds of the lease expiry.
      (Default: 60)
    - `:renew-increment`
      How long to request credentials be renewed for. (Default: 4h)
    - `:on-renew`
      A function to call with the updated lease information after the
      credentials have been renewed.
    - `:rotate?`
      If true, attempt to read a new set of credentials when they can no longer
      be renewed. (Default: false)
    - `:rotate-within`
      Rotate the secret when within this many seconds of the lease expiry.
      (Default: 60)
    - `:on-rotate`
      A function to call with the new credentials after they have been
      rotated.
    - `:on-error`
      A function to call with any exceptions encountered while renewing or
      rotating the credentials."))


;; ## Mock Client

(extend-type MockClient

  API

  (with-mount
    [client mount]
    (if (some? mount)
      (assoc client ::mount mount)
      (dissoc client ::mount)))


  (generate-credentials!
    [_client _role-name]
    ,,,))


;; ## HTTP Client

(extend-type HTTPClient

  API

  (with-mount
    [client mount]
    (if (some? mount)
      (assoc client ::mount mount)
      (dissoc client ::mount)))


  (generate-credentials!
    [client role-name opts]
    (let [mount (::mount client default-mount)
          api-path (u/join-path mount "creds" role-name)
          cache-key [::role mount role-name]]
      (if-let [data (lease/find-data (:leases client) cache-key)]
        ;; Re-use cached secret.
        ;; TODO: what happens if the caller specifies different options?
        (http/cached-response client data)
        ;; No cached value available, call API.
        (http/call-api
          client :get api-path
          {:handle-response
           (fn handle-response
             [body]
             (let [lease (http/lease-info body)
                   data (-> (get body "data")
                            (u/walk-keys keyword)
                            (vary-meta assoc
                                       ::mount mount
                                       ::role role-name))]
               (when lease
                 (lease/put!
                   (:leases client)
                   (-> lease
                       (assoc ::lease/key cache-key)
                       (comm/renewable-lease client opts)
                       (comm/rotatable-lease client opts #(generate-credentials! client role-name opts)))
                   data))
               (vary-meta data merge lease)))})))))
