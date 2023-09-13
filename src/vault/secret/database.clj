(ns vault.secret.database
  "The database secrets engine is used to manage dynamically-issued credentials
  for users of a database backend such as mysql, postgresql, mongodb, etc. The
  vault server uses a privileged 'root' user to create new users with randomized
  passwords on-demand for callers.

  Reference: https://www.vaultproject.io/api-docs/secret/databases"
  (:require
    [vault.client.http :as http]
    [vault.util :as u])
  (:import
    vault.client.http.HTTPClient))


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
    [client role-name]
    [client role-name opts]
    "Generate a new set of dynamic credentials based on the named role.

    Options:
    - `:refresh?`
      Always make a call for fresh data, even if a cached secret lease is
      available.
    - `:renew?`
      If true, attempt to automatically renew the credentials lease when near
      expiry. (Default: false)
    - `:renew-within`
      Renew the secret when within this many seconds of the lease expiry.
      (Default: 60)
    - `:renew-increment`
      How long to request credentials be renewed for, in seconds.
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


;; ## HTTP Client

(extend-type HTTPClient

  API

  (with-mount
    [client mount]
    (if (some? mount)
      (assoc client ::mount mount)
      (dissoc client ::mount)))


  (generate-credentials!
    ([client role-name]
     (generate-credentials! client role-name {}))
    ([client role-name opts]
     (let [mount (::mount client default-mount)
           api-path (u/join-path mount "creds" role-name)
           cache-key [::role mount role-name]]
       (http/generate-rotatable-credentials!
         client :get api-path
         {:info {::mount mount, ::role role-name}
          :cache-key cache-key}
         opts)))))
