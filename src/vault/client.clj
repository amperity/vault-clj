(ns vault.client
  "Core namespace for constructing a Vault client to use with the API
  protocols."
  (:import
    java.net.URI
    java.time.Instant))


;; ## Core Protocol

(defprotocol Client
  "Marker protocol that indicates an object is a valid Vault client interface."

  ;; TODO: are there common methods that makes sense here?
  ;; - status?
  ;; - authentication?
  ,,,)


;; ## Client Construction

(defmulti new-client
  "Constructs a new Vault client from a URI by dispatching on the scheme. The
  client will be returned in an initialized but not started state."
  (fn dispatch
    [uri]
    (.getScheme (URI. uri))))


(defmethod new-client :default
  [uri]
  (throw (IllegalArgumentException.
           (str "Unsupported Vault client URI scheme: " (pr-str uri)))))


;; ## Utilities

(defn ^:no-doc now
  "Implementation helper for returning the current time. Mostly useful for
  rebinding in test contexts."
  ^Instant
  []
  (Instant/now))
