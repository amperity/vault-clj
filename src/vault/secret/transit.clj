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
    "Returns information about a named encryption key. The keys object shows the
    creation time of each key version; the values are not the keys themselves.")

  (rotate-key!
    [client key-name]
    [client key-name opts]
    "Rotates the version of the named key. After rotation, new plaintext
    requests will be encrypted with the new version of the key. To upgrade
    ciphertext to be encrypted with the latest version of the key, use the
    `rewrap` endpoint.

    Options:
    - `:managed-key-name`
      The name of the managed key to use for this key.
    - `:managed-key-id`
      The UUID of the managed key to use for this key.
      One of `:managed-key-name` or `:managed-key-id` is required if the key
      type is `:managed-key`.")

  (update-key-configuration!
    [client key-name opts]
    "Updates configuration values for a given key.

    Options:
    - `:min-decryption-version`
      Minimum version of the key allowed to decrypt payloads.
    - `:min-encryption-version`
      Minimum version of the key allowed to encrypt payloads. Must be 0 (which
      specifies the latest version), or greater than `:min-decryption-version`.
    - `:deletion-allowed`
      True if the key is allowed to be deleted.
    - `:exportable`
      True if the key is be allowed to be exported. Once set, cannot be disabled.
    - `:allow-plaintext-backup`
      True if plaintext backups of the key are allowed. Once set, cannot be
      disabled.
    - `:auto-rotate-period`
      Uses duration format strings, for example: 4h, 5d.

      The period at which this key should be rotated automatically. Setting this
      to \"0\" will disable automatic key rotation. This value cannot be shorter
      than one hour. When no value is provided, the period remains unchanged.")

  (encrypt-data!
    [client key-name plaintext]
    [client key-name plaintext opts]
    "Encrypts the provided plaintext using the named key. Supports create and
    update. If a user only has update permissions and the key does not exist, an
    error will be returned.

    `plaintext` must be sent as a base64 string.

    Options:
    - `:associated-data`
      Associated data which won't be encrypted but will be authenticated. Must
      be sent as base64.
    - `:context`
      The context for key derivation. Required if key derivation is enabled.
      Must be sent as base64.
    - `:key-version`
      The version of the key to use for encryption. Uses latest version if not
      set. Must be greater than or equal to the key's `:min-encryption-version`.
    - `:nonce`
      The nonce to use for encryption. Must be sent as base64. Must be 96 bits
      (12 bytes) long and may not be reused.
    - `:reference`
      A string to help identify results when using `:batch-input`.
    - `:batch-input`
      Specifies a list of items to encrypt in a single batch. Output preserves
      order of input. Will ignore top-level `:plaintext` and related fields if
      set.
    - `:type`
      The type of key to create. Required if the key does not exist.
    - `:convergent-encryption`
      Only be used when a key is expected to be created. Whether to support
      convergent encryption. This is only supported when using a key with key
      derivation enabled and will require all requests to carry both a context and
      96-bit (12-byte) nonce. The given nonce will be used in place of a randomly
      generated nonce. As a result, when the same context and nonce are supplied,
      the same ciphertext is generated. It is very important when using this mode
      that you ensure that all nonces are unique for a given context. Failing to
      do so will severely impact the ciphertext's security.
    - `:partial-failure-response-code`
      If set, will return this HTTP response code instead of a 400 if some but
      not all members of a batch fail to encrypt.")

  (decrypt-data!
    [client key-name ciphertext]
    [client key-name ciphertext opts]
    "Decrypts the ciphertext using the named key. Returns a base64-encoded
    plaintext string.

    Options:
    - `:associated-data`
      Associated data to be authenticated (but not decrypted).
    - `:context`
      The context for key derivation. Required if key derivation is enabled.
      Must be sent as base64.
    - `:nonce`
      The nonce used for encryption. Must be sent as base64.
    - `:reference`
      A string to help identify results when using `:batch-input`.
    - `:batch-input`
      Specifies a list of items to decrypt in a single batch. Output preserves
      order of input. Will ignore top-level `:ciphertext` and related fields if
      set.
    - `:partial-failure-response-code`
      If set, will return this HTTP response code instead of a 400 if some but
      not all members of a batch fail to decrypt."))


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
        {:content-type :json
         :handle-response (fn handle-response
                            [body]
                            (u/kebabify-keys (get body "data")))})))

  (rotate-key!
    ([client key-name]
     (rotate-key! client key-name nil))
    ([client key-name opts]
     (let [mount (::mount client default-mount)]
       (http/call-api
         client :post (u/join-path mount "keys" key-name "rotate")
         {:content-type :json
          :body opts}))))

  (update-key-configuration!
    [client key-name opts]
    (let [mount (::mount client default-mount)]
      (http/call-api
        client :post (u/join-path mount "keys" key-name "config")
        {:content-type :json
         :body opts})))

  (encrypt-data!
    ([client key-name plaintext]
     (encrypt-data! client key-name plaintext nil))
    ([client key-name plaintext opts]
     (let [mount (::mount client default-mount)]
       (http/call-api
         client :post (u/join-path mount "encrypt" key-name)
         {:content-type :json
          :body (assoc opts :plaintext plaintext)
          :handle-response (fn handle-response
                             [body]
                             (u/kebabify-keys (get body "data")))}))))

  (decrypt-data!
    ([client key-name ciphertext]
     (decrypt-data! client key-name ciphertext nil))
    ([client key-name ciphertext opts]
     (let [mount (::mount client default-mount)]
       (http/call-api
         client :post (u/join-path mount "decrypt" key-name)
         {:content-type :json
          :body (u/stringify-keys (assoc opts :ciphertext ciphertext))
          :handle-response (fn handle-response
                             [body]
                             (u/kebabify-keys (get body "data")))})))))
