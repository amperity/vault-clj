(ns vault.secrets.kvv2
  "Interface for communicating with a Vault key value version 2 secret store (kv)"
  (:require
    [vault.client.api-util :as api-util]
    [vault.core :as vault]))


(defn list-secrets
  "Returns a vector of the secrets names located under a path.

  Params:
  - `client`: `vault.client`, A client that handles vault auth, leases, and basic CRUD ops
  - `mount`: `String`, the path in vault of the secret engine you wish to list secrets in
  - `path`: `String`, the path in vault of the secret you wish to list secrets at"
  [client mount path]
  (vault/list-secrets client (str mount "/metadata/" path)))


(defn read-secret
  "Reads a secret from a path. Returns the full map of stored secret data if
  the secret exists, or throws an exception if not.

  Params:
  - `client`: `vault.client`, A client that handles vault auth, leases, and basic CRUD ops
    - `mount`: `String`, the path in vault of the secret engine you wish to read a secret in
  - `path`: `String`, the path in vault of the secret you wish to read
  - `opts`: `map`, Further optional read described below.

  Additional options may include:
  - `:not-found`, `any`
    If the requested path is not found, return this value instead of throwing
    an exception.
  - `:renew`, `boolean`
    Whether or not to renew this secret when the lease is near expiry.
  - `:rotate`, `boolean`
    Whether or not to rotate this secret when the lease is near expiry and
    cannot be renewed.
  - `:force-read`, `boolean`
    Force the secret to be read from the server even if there is a valid lease cached."
  ([client mount path opts]
   (api-util/supports-not-found
     opts
     (:data (vault/read-secret client (str mount "/data/" path) (dissoc opts :not-found)))))
  ([client mount path]
   (read-secret client mount path nil)))


(defn read-metadata
  "Returns  retrieves the metadata and versions for the secret at the specified path.

  Params:
  - `client`: `vault.client`, A client that handles vault auth, leases, and basic CRUD ops
  - `mount`: `String`, the secret engine mount point you wish to read secret metadata in
  - `path`: `String`, the path in vault of the secret you wish to read metadata for
  - `opts`: `map`, options to affect the read call, see `vault.core/read-secret` for more details"
  ([client mount path opts]
   (vault/read-secret client (str mount "/metadata/" path) opts))
  ([client mount path]
   (read-metadata client mount path nil)))


(defn write-metadata!
  "Creates a new version of a secret at the specified location. If the value does not yet exist, the calling token
  must have an ACL policy granting the create capability. If the value already exists, the calling token must have an
  ACL policy granting the update capability. Returns a boolean indicating whether the write was successful.

  Params:
  - `client`: `vault.client`, A client that handles vault auth, leases, and basic CRUD ops
  - `mount`: `String`, the secret engine mount point you wish to write secret metadata in
  - `path`: `String`, the path in vault of the secret you wish to write metadata for'
  - `metadata`: `map` the metadata you wish to write.

  Metadata options are:
  -`:max_versions`: `int`, The number of versions to keep per key. This value applies to all keys, but a key's
  metadata setting can overwrite this value. Once a key has more than the configured allowed versions the oldest
  version will be permanently deleted. Defaults to 10.
  -`:cas_required`: `boolean`, – If true all keys will require the cas parameter to be set on all write requests.
  - :delete_version_after` `String` – If set, specifies the length of time before a version is deleted.
  Accepts Go duration format string."
  [client mount path metadata]
  (vault/write-secret! client (str mount "/metadata/" path) metadata))


(defn write-secret!
  "Writes secret data to a path. Returns a boolean indicating whether the write was successful.

  Params:
  - `client`: `vault.client`, A client that handles vault auth, leases, and basic CRUD ops
  - `mount`: `String`, the path in vault of the secret engine you wish to write a secret in
  - `path`: `String`, the path of the secret you wish to write the data to
  - `data`: `map`, the secret data you wish to write"
  [client mount path data]
  ;; this gets the mock client to also write metadata, and shouldn't meaningfully affect the http client
  (write-metadata! client mount path {})
  (let [result (vault/write-secret! client (str mount "/data/" path) {:data data})]
    (or (:data result) result)))


(defn write-config!
  "Configures backend level settings that are applied to every key in the key-value store for a given secret engine.
   Returns a boolean indicating whether the write was successful.

  Params:
  - `client`: `vault.client`, A client that handles vault auth, leases, and basic CRUD ops
  - `mount`: `String`, the path in vault of the secret engine you wish to configure
  - `config`: `map`, the configurations you wish to write.

  Configuration options are:
  -`:max_versions`: `int`, The number of versions to keep per key. This value applies to all keys, but a key's
  metadata setting can overwrite this value. Once a key has more than the configured allowed versions the oldest
  version will be permanently deleted. Defaults to 10.
  - `:cas_required`: `boolean`, – If true all keys will require the cas parameter to be set on all write requests.
  - `:delete_version_after` `String` – If set, specifies the length of time before a version is deleted.
  Accepts Go duration format string."

  [client mount config]
  (vault/write-secret! client (str mount "/config") config))


(defn read-config
  "Returns the current configuration for the secrets backend at the given path (mount)

  Params:
  - `client`: `vault.client`, A client that handles vault auth, leases, and basic CRUD ops
  - `mount`: `String`, the path in vault of the secret engine you wish to read configurations for
  - `opts`: `map`, options to affect the read call, see `vault.core/read-secret` for more details"
  ([client mount opts]
   (vault/read-secret client (str mount "/config") opts))
  ([client mount]
   (read-config client mount nil)))


(defn destroy-secret!
  "Permanently removes the specified version data for the provided key and version numbers from the key-value store.
Returns a boolean indicating whether the destroy was successful.

Params:
- `client`: `vault.client`, A client that handles vault auth, leases, and basic CRUD ops
- `mount`: `String`, the path in vault of the secret engine you wish to configure
- `path`: `String`, the path aligned to the secret you wish to destroy
- `versions`: `vector<int>`, the versions you want to destroy"
  [client mount path versions]
  (vault/write-secret! client (str mount "/destroy/" path) {:versions versions}))


(defn undelete-secret!
  "Undeletes the data for the provided version and path in the key-value store. This restores the data, allowing it to
be returned on get requests.

Params:
- `client`: `vault.client`, A client that handles vault auth, leases, and basic CRUD ops
- `mount`: `String`, the path in vault of the secret engine you wish to configure
- `path`: `String`, the path aligned to the secret you wish to undelete
- `versions`: `vector<int>`, the versions you want to undelete"
  [client mount path versions]
  (vault/write-secret! client (str mount "/undelete/" path) {:versions versions}))


(defn delete-secret!
  "Performs a soft delete a secret. This marks the versions as deleted and will stop them from being returned from
  reads, but the underlying data will not be removed. A delete can be undone using the `undelete` path.

  - `client`: `vault.client`, A client that handles vault auth, leases, and basic CRUD ops
  - `mount`: `String`, the Vault secret mount (the part of the path which determines which secret engine is used)
  - `path`: `String`, the path aligned to the secret you wish to delete
  - `versions`: `vector<int>`, the versions of that secret you wish to delete, defaults to deleting the latest version"
  ([client mount path versions]
   (if (empty? versions)
     (vault/delete-secret! client (str mount "/data/" path))
     (vault/write-secret! client (str mount "/delete/" path) {:versions versions})))
  ([client mount path]
   (delete-secret! client mount path nil)))


(defn delete-metadata!
  "Permanently deletes the key metadata and all version data for the specified key.
  All version history will be removed. This cannot be undone. A boolean indicating deletion success is returned.

  - `client`: `vault.client`, A client that handles vault auth, leases, and basic CRUD ops
  - `mount`: `String`, the Vault secret mount (the part of the path which determines which secret engine is used)
  - `path`: `String`, the path aligned to the secret you wish to delete all data for"
  [client mount path]
  (vault/delete-secret! client (str mount "/metadata/" path)))
