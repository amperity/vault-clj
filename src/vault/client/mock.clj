(ns vault.client.mock
  "Defines the mock Vault client"
  (:require
    [clojure.edn :as edn]
    [clojure.java.io :as io]
    [clojure.string :as str]
    [vault.client.api-util :as api-util]
    [vault.core :as vault])
  (:import
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
    [this opts]
    (let [dates {\s 1 \m 60 \h 3600 \d 86400}

          ttl-string->seconds-int
          (fn [ttl]
            (* (read-string (subs ttl 0 (dec (count ttl))))
               (or (get dates (last ttl))
                   (throw (ex-info (str "Mock Client doesn't recognize format of ttl: " ttl)
                                   {:opts opts :client this})))))]
      (if (:wrap-ttl opts)
        (let [wrap-token (gen-uuid)]
          (swap! cubbies assoc wrap-token (mock-token-auth))
          {:token            wrap-token
           :ttl              (ttl-string->seconds-int (:wrap-ttl opts))
           :creation-time    (gen-date)
           :wrapped-accessor (gen-uuid)
           :accessor         (gen-uuid)
           :creation-path    "auth/token/create"})

        ;; unwrapped
        (let [policies (cond
                         (and (:policies opts) (:no-default-policy opts))
                         (get opts :policies ["root"])

                         (contains? opts :policies)
                         (into ["default"] (:policies opts))

                         :else
                         ["root"])]
          {:policies       policies
           :renewable      (get opts :renewable false)
           :entity-id      ""
           :token-policies policies
           :accessor       (gen-uuid)
           :lease-duration (if (:ttl opts)
                             (ttl-string->seconds-int (:ttl opts))
                             0)
           :token-type     "service"
           :orphan         (get opts :no-parent false)
           :client-token   (gen-uuid)
           :metadata       nil}))))


  (lookup-token
    [this]
    ; TODO: implement
    (throw (UnsupportedOperationException. "NYI")))


  (lookup-token
    [this token]
    ; TODO: implement
    (throw (UnsupportedOperationException. "NYI")))


  (renew-token
    [this]
    ; TODO: implement
    (throw (UnsupportedOperationException. "NYI")))


  (renew-token
    [this token]
    ; TODO: implement
    (throw (UnsupportedOperationException. "NYI")))


  (revoke-token!
    [this]
    ; TODO: implement
    (throw (UnsupportedOperationException. "NYI")))


  (revoke-token!
    [this token]
    ; TODO: implement
    (throw (UnsupportedOperationException. "NYI")))


  (lookup-accessor
    [this token-accessor]
    ; TODO: implement
    (throw (UnsupportedOperationException. "NYI")))


  (revoke-accessor!
    [this token]
    ; TODO: implement
    (throw (UnsupportedOperationException. "NYI")))


  vault/LeaseManager

  (list-leases
    [this]
    [])


  (renew-lease
    [this lease-id]
    ; TODO: implement
    (throw (UnsupportedOperationException. "NYI")))


  (revoke-lease!
    [this lease-id]
    true)


  (add-lease-watch
    [this watch-key path watch-fn]
    this)


  (remove-lease-watch
    [this watch-key]
    this)


  vault/SecretEngine

  (list-secrets
    [this path]
    (->> (keys @memory)
         (filter #(str/starts-with? % (str path)))
         ;; TODO: Mock here relies on string replace to get correct result for kvv2, this is brittle and not extensible
         (map #(str/replace % #"(?:\w+\/)+metadata\/" ""))))


  (read-secret
    [this path opts merge-req]
    (or (get @memory path)
        (if (contains? opts :not-found)
          (:not-found opts)
          (throw (ex-info (str "No such secret: " path)
                          {:secret path
                           :type ::api-util/api-error
                           :status 404})))))

  (read-secret
    [this path opts]
    (.read-secret this path opts {}))


  (write-secret!
    [this path data]
    (swap! memory assoc path data)
    true)


  (delete-secret!
    [this path]
    (let [was-in-memeory (contains? @memory path)]
      (swap! memory dissoc path)
      was-in-memeory))


  vault/WrappingClient

  (wrap!
    [this data ttl]
    (let [wrap-token (gen-uuid)]
      (swap! cubbies assoc wrap-token data)
      {:token wrap-token
       :ttl 3600  ; ideally, set this from `ttl`
       :creation-time (gen-date)}))


  (unwrap!
    [this wrap-token]
    (if-let [data (get @cubbies wrap-token)]
      (do (swap! cubbies dissoc wrap-token)
          data)
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
