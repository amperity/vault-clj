(ns vault.secret.transit
  "The transit secrets engine handles cryptographic functions on data
  in-transit. It can also be viewed as \"cryptography as a service\" or
  \"encryption as a service\". The transit secrets engine can also sign and
  verify data; generate hashes and HMACs of data; and act as a source of random
  bytes.

  Reference: https://www.vaultproject.io/api-docs/secret/transit"
  (:require
    [vault.client.http :as http]
    [vault.util :as u])
  (:import
    java.time.Instant
    vault.client.http.HTTPClient))


(def default-mount "transit")


(defprotocol API

  (with-mount
    [client mount]
    "Return an updated client which will resolve secrets against the provided
    mount instead of the default. Passing `nil` will reset the client to the
    default.")

  (read-key
    [client key-name]
    "Look up information about a named encryption key. The keys object shows
    the creation time of each key version; the values are not the keys
    themselves.")

  (rotate-key!
    [client key-name]
    [client key-name opts]
    "Rotate the version of the named key. Returns the key information map.

    After rotation, new encryption requests will use the new version of the
    key. To upgrade existing ciphertext to be encrypted with the latest version
    of the key, use `rewrap`.

    Options:

    - `:managed-key-name` (string)

      The name of the managed key to use for this key.

    - `:managed-key-id` (string)

      The UUID of the managed key to use for this key. One of
      `:managed-key-name` or `:managed-key-id` is required if the key type is
      `:managed-key`.")

  (update-key-configuration!
    [client key-name opts]
    "Update configuration values for a given key. Returns the key information
    map.

    Options:

    - `:min-decryption-version` (integer)

      Minimum version of the key allowed to decrypt payloads.

    - `:min-encryption-version` (integer)

      Minimum version of the key allowed to encrypt payloads. Must be `0`
      (which specifies the latest version), or greater than `:min-decryption-version`.

    - `:deletion-allowed` (boolean)

      True if the key is allowed to be deleted.

    - `:exportable` (boolean)

      True if the key is be allowed to be exported. Once set, cannot be disabled.

    - `:allow-plaintext-backup` (boolean)

      True if plaintext backups of the key are allowed. Once set, cannot be
      disabled.

    - `:auto-rotate-period` (string)

      The period at which this key should be rotated automatically, expressed
      as a duration format string. Setting this to \"0\" will disable automatic
      key rotation. This value cannot be shorter than one hour. When no value
      is provided, the period remains unchanged.")

  (encrypt-data!
    [client key-name data]
    [client key-name data opts]
    "Encrypt data using the named key. Supports create and update. If a user
    only has update permissions and the key does not exist, an error will be
    returned.

    In single-item mode, `data` may either be a string or a byte array, and
    will be automatically base64-encoded. Returns a map with the `:ciphertext`
    string and the `:key-version` used to encrypt it.

    For batch operation, `data` should be a sequence of maps, each containing
    their own `:plaintext` and optional `:context`, `:nonce`, and `:reference`
    entries. Returns a vector of batch results, each with `:ciphertext`,
    `:key-version`, and `:reference`.

    Options:

    - `:associated-data` (string or bytes)

      Associated data which won't be encrypted but will be authenticated.
      Automatically base64-encoded.

    - `:context` (string or bytes)

      The context for key derivation. Required if key derivation is enabled.
      Automatically base64-encoded.

    - `:key-version` (integer)

      The version of the key to use for encryption. Uses latest version if not
      set. Must be greater than or equal to the key's `:min-encryption-version`.

    - `:nonce` (bytes)

      The nonce to use for encryption. This must be 96 bits (12 bytes) long and
      may not be reused. Automatically base64-encoded.

    - `:reference` (string)

      A string to help identify results when using batch mode. No effect in
      single-item mode.

    - `:type` (string)

      The type of key to create. Required if the key does not exist.

    - `:convergent-encryption` (boolean)

      Whether to support convergent encryption on a new key. See the Vault docs
      for details.

    - `:partial-failure-response-code` (integer)

      If set, will return this HTTP response code instead of a 400 if some but
      not all members of a batch fail to encrypt.")

  (decrypt-data!
    [client key-name data]
    [client key-name data opts]
    "Decrypt data using the named key.

    In single-item mode, `data` should be the ciphertext string returned from
    [[encrypt-data!]]. Returns a map with the `:plaintext` data decoded into a
    byte array.

    In batch mode, `data` should be a sequence of maps, each containing their
    own `:ciphertext` and optional `:context`, `:nonce`, and `:reference`
    entries. Returns a vector of batch results, each with `:plaintext` and
    `:reference`.

    Options:

    - `:as-string` (boolean)

      Set to true to have the plaintext data decoded into a string instead of a
      byte array.

    - `:associated-data` (string)

      Associated data to be authenticated (but not decrypted).

    - `:context` (string or bytes)

      The context for key derivation. Required if key derivation is enabled.
      Automatically base64-encoded.

    - `:nonce` (bytes)

      The nonce used for encryption. Automatically base64-encoded.

    - `:reference` (string)

      A string to help identify results when using batch mode. No effect in
      single-item mode.

    - `:partial-failure-response-code` (integer)

      If set, will return this HTTP response code instead of a 400 if some but
      not all members of a batch fail to decrypt."))


;; ## HTTP Client

(defn- parse-key-info
  "Parse the key information map returned by [[read-key]] and [[rotate-key!]]."
  [body]
  (let [data (u/kebabify-body-data body)
        versions (into {}
                       (map (fn parse-version
                              [[version created-at]]
                              [(or (parse-long (name version)) version)
                               (try
                                 (Instant/ofEpochSecond created-at)
                                 (catch Exception _
                                   created-at))]))
                       (:keys data))]
    (assoc data :keys versions)))


(extend-type HTTPClient

  API

  (with-mount
    [client mount]
    (if (some? mount)
      (assoc client ::mount mount)
      (dissoc client ::mount)))


  (read-key
    [client key-name]
    (let [mount (::mount client default-mount)]
      (http/call-api
        client :get (u/join-path mount "keys" key-name)
        {::info {::mount mount, ::key key-name}
         :content-type :json
         :handle-response parse-key-info})))


  (rotate-key!
    ([client key-name]
     (rotate-key! client key-name nil))
    ([client key-name opts]
     (let [mount (::mount client default-mount)]
       (http/call-api
         client :post (u/join-path mount "keys" key-name "rotate")
         {:info {::mount mount, ::key key-name}
          :content-type :json
          :body (u/snakify-keys opts)
          :handle-response parse-key-info}))))


  (update-key-configuration!
    [client key-name opts]
    (let [mount (::mount client default-mount)]
      (http/call-api
        client :post (u/join-path mount "keys" key-name "config")
        {:info {::mount mount, ::key key-name}
         :content-type :json
         :body (u/snakify-keys opts)
         :handle-response parse-key-info})))


  (encrypt-data!
    ([client key-name data]
     (encrypt-data! client key-name data nil))
    ([client key-name data opts]
     (when-not (or (string? data) (bytes? data) (coll? data))
       (throw (IllegalArgumentException.
                (str "Expected data to be a string, bytes, or a batch collection; got: "
                     (class data)))))
     (let [mount (::mount client default-mount)
           batch (when (coll? data)
                   (mapv (fn prepare-batch
                           [entry]
                           (-> entry
                               (select-keys [:plaintext :context :nonce :reference])
                               (u/update-some :context u/base64-encode)
                               (u/update-some :nonce u/base64-encode)
                               (update :plaintext u/base64-encode)))
                         data))]
       (http/call-api
         client :post (u/join-path mount "encrypt" key-name)
         {:info {::mount mount, ::key key-name}
          :content-type :json
          :body (-> (if batch
                      (assoc opts :batch-input batch)
                      (assoc opts :plaintext (u/base64-encode data)))
                    (u/update-some :associated-data u/base64-encode)
                    (u/update-some :context u/base64-encode)
                    (u/update-some :nonce u/base64-encode)
                    (u/snakify-keys))
          :handle-response (fn coerce-response
                             [body]
                             (let [data (u/kebabify-body-data body)]
                               (or (:batch-results data)
                                   data)))}))))


  (decrypt-data!
    ([client key-name data]
     (decrypt-data! client key-name data nil))
    ([client key-name data opts]
     (when-not (or (string? data) (coll? data))
       (throw (IllegalArgumentException.
                (str "Expected data to be a string or a batch collection; got: "
                     (class data)))))
     (let [mount (::mount client default-mount)
           batch (when (coll? data)
                   (mapv (fn prepare-batch
                           [entry]
                           (-> entry
                               (select-keys [:ciphertext :context :nonce :reference])
                               (u/update-some :context u/base64-encode)
                               (u/update-some :nonce u/base64-encode)))
                         data))
           decode-plaintext (fn decode-plaintext
                              [data]
                              (u/update-some data :plaintext u/base64-decode (:as-string opts)))]
       (http/call-api
         client :post (u/join-path mount "decrypt" key-name)
         {:info {::mount mount, ::key key-name}
          :content-type :json
          :body (-> (if (string? data)
                      (assoc opts :ciphertext data)
                      (assoc opts :batch-input batch))
                    (dissoc :as-string)
                    (u/update-some :associated-data u/base64-encode)
                    (u/update-some :context u/base64-encode)
                    (u/update-some :nonce u/base64-encode)
                    (u/snakify-keys))
          :handle-response (fn coerce-response
                             [body]
                             (let [data (u/kebabify-body-data body)
                                   batch (:batch-results data)]
                               (if batch
                                 (mapv decode-plaintext batch)
                                 (decode-plaintext data))))})))))
