(ns ^:no-doc vault.client.proto
  "Core Vault client protocol.")


(defprotocol Client
  "Marker protocol that indicates an object is a valid Vault client interface."

  (auth-info
    [client]
    "Return the client's current auth information, a map containing the
    `:vault.auth/token` and other metadata keys from the `vault.auth`
    namespace. Returns nil if the client is unauthenticated.")

  (authenticate!
    [client auth-info]
    "Manually authenticate the client by providing a map of auth information
    containing a `:vault.auth/token`. As a shorthand, a Vault token string may
    be provided directly. Returns the client."))
