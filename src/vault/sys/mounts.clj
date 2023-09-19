(ns vault.sys.mounts
  "The `/sys/mounts` endpoint is used to manage secrets engines in Vault.

  Reference: https://www.vaultproject.io/api-docs/system/mounts"
  (:require
    [vault.client.http :as http]
    [vault.util :as u])
  (:import
    vault.client.http.HTTPClient))


;; ## API Protocol

(defprotocol API
  "Methods for managing secrets engines in Vault."

  (list-mounts
    [client]
    "List all the mounted secrets engines. Returns a map of secrets engines to
    their configurations.")

  (enable-secrets!
    [client path params]
    "Enable a new secrets engine at the given path. After enabling, this engine
    can be accessed and configured via the specified path. Returns nil.

    Parameters:

    - `:type` (string)

      The type of the backend, such as \"aws\" or \"openldap\".

    - `:description (optional, string)

      Human-friendly description of the mount.

    - `:config` (optional, map)

      Configuration options for this mount.

    - `:options` (optional, map)

      Mount type specific options that are passed to the backend.

    See the Vault API docs for details.")

  (disable-secrets!
    [client path]
    "Disable the mount point specified by the given path.")

  (read-secrets-configuration
    [client path]
    "Read the configuration of the secrets engine mounted at the given path.")

  (read-mount-configuration
    [client path]
    "Read the given mount's configuration.

    Unlike the [[read-secrets-configuration]] method, this will return the
    current time in seconds for each TTL, which may be the system default or a
    mount-specific value.")

  (tune-mount-configuration!
    [client path params]
    "Tune the configuration parameters for the given mount point. Returns
    `nil`.

    See the Vault API docs for available parameters."))


;; ## HTTP Client

(extend-type HTTPClient

  API

  (list-mounts
    [client]
    (http/call-api
      client ::list-mounts
      :get "sys/mounts"
      {:handle-response
       (fn handle-response
         [body]
         (into {}
               (map (juxt key (comp u/kebabify-keys val)))
               (get body "data")))}))


  (enable-secrets!
    [client path params]
    (http/call-api
      client ::enable-secrets!
      :post (u/join-path "sys/mounts" path)
      {:info {::path path, ::type (:type params)}
       :content-type :json
       :body (u/snakify-keys params)}))


  (disable-secrets!
    [client path]
    (http/call-api
      client ::disable-secrets!
      :delete (u/join-path "sys/mounts" path)
      {:info {::path path}}))


  (read-secrets-configuration
    [client path]
    (http/call-api
      client ::read-secrets-configuration
      :get (u/join-path "sys/mounts" path)
      {:info {::path path}
       :handle-response u/kebabify-body-data}))


  (read-mount-configuration
    [client path]
    (http/call-api
      client ::read-mount-configuration
      :get (u/join-path "sys/mounts" path "tune")
      {:info {::path path}
       :handle-response u/kebabify-body-data}))


  (tune-mount-configuration!
    [client path params]
    (http/call-api
      client ::tune-mount-configuration!
      :post (u/join-path "sys/mounts" path "tune")
      {:info {::path path}
       :content-type :json
       :body (u/snakify-keys params)})))
