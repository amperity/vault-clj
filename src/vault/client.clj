(ns vault.client
  "Core Vault client namespace.")


;; ## Core Protocol

(defprotocol Client
  "Marker protocol that indicates an object is a valid Vault client interface."

  (auth-info
    [client]
    "Return the client's current auth information, a map containing the
    `:client-token` and other metadata. Returns nil if the client is
    unauthenticated.")

  (authenticate!
    [client auth-info]
    "Manually authenticate the client by providing a map of auth information
    containing a `:client-token`. As a shorthand, a Vault token string may be
    provided directly. Returns the client."))
