(ns vault.secret.aws
  "The AWS secrets engine generates AWS access credentials dynamically based on
  IAM policies.

  Reference: https://www.vaultproject.io/api-docs/secret/aws"
  (:require
    [vault.client.http :as http]
    [vault.util :as u])
  (:import
    vault.client.http.HTTPClient))


(def default-mount
  "Default mount point to use if one is not provided."
  "aws")


;; ## API Protocol

(defprotocol API
  "The AWS secrets engine generates AWS access credentials dynamically based on
  IAM policies."

  (with-mount
    [client mount]
    "Return an updated client which will resolve calls against the provided
    mount instead of the default. Passing `nil` will reset the client to the
    default.")

  (generate-user-credentials!
    [client user-name]
    [client user-name opts]
    "Generate a new set of dynamic IAM credentials based on the named user.

    Options:

    - `:refresh?` (boolean)

      Always make a call for fresh data, even if a cached secret lease is
      available.

    - `:renew?` (boolean)

      If true, attempt to automatically renew the credentials lease when near
      expiry. (Default: `false`)

    - `:renew-within` (integer)

      Renew the secret when within this many seconds of the lease expiry.
      (Default: `60`)

    - `:renew-increment` (integer)

      How long to request credentials be renewed for, in seconds.

    - `:on-renew` (fn)

      A function to call with the updated lease information after the
      credentials have been renewed.

    - `:rotate?` (boolean)

      If true, attempt to read a new set of credentials when they can no longer
      be renewed. (Default: `false`)

    - `:rotate-within` (integer)

      Rotate the secret when within this many seconds of the lease expiry.
      (Default: `60`)

    - `:on-rotate` (fn)

      A function to call with the new credentials after they have been
      rotated.

    - `:on-error` (fn)

      A function to call with any exceptions encountered while renewing or
      rotating the credentials.")

  (generate-role-credentials!
    [client role-name]
    [client role-name opts]
    "Generate a new set of dynamic IAM credentials based on the named role.

    Options:

    - `:refresh?` (boolean)

      Always make a call for fresh data, even if a cached secret lease is
      available.

    - `:rotate?` (boolean)

      If true, attempt to read a new set of credentials when they can no longer
      be renewed. (Default: `false`)

    - `:rotate-within` (integer)

      Rotate the secret when within this many seconds of the lease expiry.
      (Default: `60`)

    - `:on-rotate` (fn)

      A function to call with the new credentials after they have been
      rotated.

    - `:on-error` (fn)

      A function to call with any exceptions encountered while generating or
      rotating the credentials."))


;; ## HTTP Client

(extend-type HTTPClient

  API

  (with-mount
    [client mount]
    (if (some? mount)
      (assoc client ::mount mount)
      (dissoc client ::mount)))


  (generate-user-credentials!
    ([client user-name]
     (generate-user-credentials! client user-name {}))
    ([client user-name opts]
     (let [mount (::mount client default-mount)
           api-path (u/join-path mount "creds" user-name)
           cache-key [::user mount user-name]]
       (http/generate-rotatable-credentials!
         client ::generate-user-credentials!
         :get api-path
         {:info {::mount mount, ::user user-name}
          :cache-key cache-key}
         opts))))


  (generate-role-credentials!
    ([client role-name]
     (generate-role-credentials! client role-name {}))
    ([client role-name opts]
     (let [mount (::mount client default-mount)
           api-path (u/join-path mount "sts" role-name)
           cache-key [::role mount role-name]]
       (http/generate-rotatable-credentials!
         client ::generate-role-credentials!
         :get api-path
         {:info {::mount mount, ::role role-name}
          :cache-key cache-key}
         (assoc opts
                ;; STS credentials are not renewable
                :renew? false))))))
