(ns vault.client
  "Core namespace for constructing a Vault client to use with the API
  protocols."
  (:import
    java.net.URI))


;; ## Core Protocol

(defprotocol Client
  "Marker protocol that indicates an object is a valid Vault client interface."

  ;; TODO: are there common methods that makes sense here?
  ;; - status?
  ;; - authentication?
  ,,,)


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
