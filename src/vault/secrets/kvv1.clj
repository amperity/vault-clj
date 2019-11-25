(ns vault.secrets.kvv1
  (:require
    [vault.core :as vault]))


(defn list-secrets
  "Returns a vector of the secrets names located under a path.

  Params:
  - `client`: `vault.client`, A client that handles vault auth and reading
  - `path`: `String`, the path in vault of the secret you wish to read"
  [client path]
  (vault/list-secrets client path))


(defn read-secret
  "Reads a secret from a path. Returns the full map of stored secret data if
  the secret exists, or throws an exception if not.

  Params:
  - `client`: `vault.client`, A client that handles vault auth and reading
  - `path`: `String`, the path in vault of the secret you wish to read
  - `opts`: `map`, Further optional read described below.

  Additional options may include:

  - `:not-found`
    If the requested path is not found, return this value instead of throwing
    an exception.
  - `:renew`
    Whether or not to renew this secret when the lease is near expiry.
  - `:rotate`
    Whether or not to rotate this secret when the lease is near expiry and
    cannot be renewed.
  - `:force-read`
    Force the secret to be read from the server even if there is a valid lease cached."
  ([client path opts]
   (vault/read-secret client path opts))
  ([client path]
   (read-secret client path nil)))


(defn write-secret!
  "Writes secret data to a path. Returns a boolean indicating whether the write was successful.

   Params:
   - `client`: `vault.client`, A client that handles vault auth and reading
   - `path`: `String`, the path in vault of the secret you wish to read
   - `data`: `map`, The data you wish to write to the given secret path."
  [client path data]
  (vault/write-secret! client path data))


(defn delete-secret!
  "Removes secret data from a path. Returns a boolean indicating whether the deletion was successful.

   Params:
   - `client`: `vault.client`, A client that handles vault auth and reading
   - `path`: `String`, the path in vault of the secret you wish to read"
  [client path]
  (vault/delete-secret! client path))
