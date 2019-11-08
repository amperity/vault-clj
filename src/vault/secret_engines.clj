(ns vault.secret-engines)


(defprotocol SecretEngine
  "Basic API for listing, reading, and writing secrets.

  `eng` is a keyword representing the secret engine/mount"

  (list-secrets
    [client path eng]
    "List the secrets located under a path.")

  (read-secret
    [client path opts eng]
    "Reads a secret from a path. Returns the full map of stored secret data if
    the secret exists, or throws an exception if not.

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
      Force the secret to be read from the server even if there is a valid lease cached.")

  (write-secret!
    [client path data eng]
    "Writes secret data to a path. `data` should be a map. Returns a
    boolean indicating whether the write was successful.")

  (delete-secret!
    [client path eng]
    "Removes secret data from a path. Returns a boolean indicating whether the
    deletion was successful."))
