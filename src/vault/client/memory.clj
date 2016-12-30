(ns vault.client.memory
  (:require
    [clojure.string :as str]
    [clojure.tools.logging :as log]
    [vault.core :as vault])
  (:import
    java.text.SimpleDateFormat
    (java.util
      Date
      UUID)))


(defn- gen-date
  "Generates a formatted date-time string for the current instant."
  []
  (let [f (java.text.SimpleDateFormat. "yyyy-MM-DDHH:mm:ss.SSSZ")]
    (.format f (Date.))))


(defn- gen-uuid
  "Generates a random UUID string."
  []
  (str (UUID/randomUUID)))


(defn- mock-token-auth
  "Generates a mock token response for use in the mock client."
  []
  {:client-token (gen-uuid)
   :accessor (gen-uuid)
   :policies ["root"]
   :metadata nil
   :lease-duration 0
   :renewable false})


(defrecord MemoryClient
  [memory cubbies]

  vault/Client

  (authenticate!
    [this auth-type credentials]
    this)

  (status
    [this]
    {:initialized true
     :sealed false
     :standby false
     :server-time-utc (/ (System/currentTimeMillis) 1000)})


  vault/TokenManager

  (create-token!
    [this]
    (.create-token! this nil))

  (create-token!
    [this opts]
    {:request-id ""
     :lease-id ""
     :renewable false
     :lease-duration 0
     :data nil
     :wrap-info
     (when (:wrap-ttl opts)
       (let [wrap-token (gen-uuid)]
         (swap! cubbies assoc wrap-token (mock-token-auth))
         {:token wrap-token
          :ttl (:wrap-ttl opts)
          :creation-time (gen-date)
          :wrapped-accessor (gen-uuid)}))
     :warnings nil
     :auth (when-not (:wrap-ttl opts) (mock-token-auth))})

  ; TODO: lookup-token
  ; TODO: lookup-accessor
  ; TODO: renew-token
  ; TODO: revoke-token!
  ; TODO: revoke-accessor!


  vault/LeaseManager

  ; TODO: list-lease
  ; TODO: renew-lease
  ; TODO: revoke-lease!


  vault/SecretClient

  (list-secrets
    [this path]
    (filter #(str/starts-with? % (str path)) (keys @memory)))

  (read-secret
    [this path]
    (or (get @memory path)
        (throw (ex-info (str "No such secret: " path) {:secret path}))))

  (write-secret!
    [this path data]
    (swap! memory assoc path data)
    true)

  (delete-secret!
    [this path]
    (swap! memory dissoc path)
    true)


  vault/WrappingClient

  ; TODO: wrap!

  (unwrap!
    [this wrap-token]
    (if-let [token (get @cubbies wrap-token)]
      (do
        (swap! cubbies dissoc wrap-token)
        token)
      (throw (ex-info "Unknown wrap-token used" {})))))



;; ## Constructors

;; Privatize automatic constructors.
(alter-meta! #'->MemoryClient assoc :private true)
(alter-meta! #'map->MemoryClient assoc :private true)


(defn memory-client
  "Constructs a new mock in-memory Vault client."
  ([]
   (memory-client {} {}))
  ([initial-memory]
   (memory-client initial-memory {}))
  ([initial-memory initial-cubbies]
   (map->MemoryClient
     {:memory (atom initial-memory :validator map?)
      :cubbies (atom initial-cubbies :validator map?)})))


(defmethod vault/new-client "mem"
  [location]
  (memory-client))
