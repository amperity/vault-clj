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
    deletion was successful.")

  (write-config!
    [client path data eng]
    "Writes configurations at the given path

    Data is the body of a request specifying:
    - `max_versions` – The number (as an int) of versions to keep per key.
    This value applies to all keys, but a key's metadata setting can overwrite this value.
    Once a key has more than the configured allowed versions the oldest version will
    be permanently deleted. Defaults to 10.
    - `can-required` - If true all keys will require the cas parameter to be set on all write requests.
    - `delete_versions_after` - String that pecifies the length of time before a version is deleted.
    Accepts Go duration format string.")

  (read-config
    [client path eng]
    "Reads configurations at the given path"))

