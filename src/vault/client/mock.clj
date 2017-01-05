(ns vault.client.mock
  (:require
    [clojure.edn :as edn]
    [clojure.java.io :as io]
    [clojure.string :as str]
    [clojure.tools.logging :as log]
    [vault.core :as vault])
  (:import
    java.text.SimpleDateFormat
    java.net.URI
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


(defrecord MockClient
  [auth memory cubbies]

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

  (lookup-token
    [this]
    ; TODO: implement
    ,,,)

  (lookup-token
    [this token]
    ; TODO: implement
    ,,,)

  (renew-token
    [this]
    ; TODO: implement
    ,,,)

  (renew-token
    [this token]
    ; TODO: implement
    ,,,)

  (revoke-token!
    [this]
    ; TODO: implement
    ,,,)

  (revoke-token!
    [this token]
    ; TODO: implement
    ,,,)

  (lookup-accessor
    [this token-accessor]
    ; TODO: implement
    ,,,)

  (revoke-accessor!
    [this token]
    ; TODO: implement
    ,,,)


  vault/LeaseManager

  (list-leases
    [this]
    [])

  (renew-lease
    [this lease-id]
    {,,,})

  (revoke-lease!
    [this lease-id]
    true)

  (add-lease-watch
    [this watch-key path watch-fn]
    this)

  (remove-lease-watch
    [this watch-key]
    this)


  vault/SecretClient

  (list-secrets
    [this path]
    (filter #(str/starts-with? % (str path)) (keys @memory)))

  (read-secret
    [this path]
    (.read-secret this nil))

  (read-secret
    [this path opts]
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

  (wrap!
    [this data]
    ; TODO: implement
    {})

  (unwrap!
    [this wrap-token]
    (if-let [token (get @cubbies wrap-token)]
      (do
        (swap! cubbies dissoc wrap-token)
        token)
      (throw (ex-info "Unknown wrap-token used" {})))))



;; ## Constructors

;; Privatize automatic constructors.
(alter-meta! #'->MockClient assoc :private true)
(alter-meta! #'map->MockClient assoc :private true)


(defn mock-client
  "Constructs a new mock in-memory Vault client."
  ([]
   (mock-client {} {}))
  ([initial-memory]
   (mock-client initial-memory {}))
  ([initial-memory initial-cubbies]
   (map->MockClient
     {:auth (atom nil)
      :memory (atom initial-memory :validator map?)
      :cubbies (atom initial-cubbies :validator map?)})))


(defn- load-fixtures
  "Helper method to load fixture data from a path. The path may resolve to a
  resource on the classpath, a file on the filesystem, or be `-` to specify no
  data."
  [location]
  (when (not= location "-")
    (some->
      (or (io/resource location)
          (let [file (io/file location)]
            (when (.exists file)
              file)))
      (slurp)
      (edn/read-string))))


(defmethod vault/new-client "mock"
  [location]
  (let [uri (URI. location)
        location (.getSchemeSpecificPart uri)
        data (load-fixtures location)]
    (mock-client (or data {}))))
