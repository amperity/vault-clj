(ns vault.client
  "Core namespace for constructing a Vault client to use with the API
  protocols."
  (:import
    java.net.URI))


;; ## Core Protocol

(defprotocol Client
  "Marker protocol that indicates an object is a valid Vault client interface."

  (authenticate!
    [client auth-info]
    "Manually authenticate the client by providing a map of auth information
    containing a `:client-token`. As a shorthand, a Vault token string may be
    provided directly."))


;; ## Client Construction

(defmulti new-client
  "Constructs a new Vault client from a URI address by dispatching on the
  scheme. The client will be returned in an initialized but not started state."
  (fn dispatch
    [address]
    (.getScheme (URI. address))))


(defmethod new-client :default
  [address]
  (throw (IllegalArgumentException.
           (str "Unsupported Vault address scheme: " (pr-str address)))))
