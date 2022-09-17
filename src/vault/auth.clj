(ns vault.auth
  "High-level namespace for client authentication."
  (:require
    [clojure.spec.alpha :as s]
    [clojure.tools.logging :as log]
    [vault.util :as u]))


;; ## Data Specs

;; Token accessor id string.
(s/def ::accessor string?)


;; Authentication token string.
(s/def ::client-token string?)


;; Display name for the token.
(s/def ::display-name string?)


;; Number of seconds the token lease is valid for.
(s/def ::lease-duration nat-int?)


;; Set of policies applied to the token.
(s/def ::policies (s/coll-of string? :kind set?))


;; Whether the token is an orphan.
(s/def ::orphan? boolean?)


;; Whether the token is renewable.
(s/def ::renewable? boolean?)


;; Instant after which the token can be renewed again.
(s/def ::renew-after inst?)


;; Instant the token was created.
(s/def ::created-at inst?)


;; Instant the token expires.
(s/def ::expires-at inst?)


;; Full auth information map.
(s/def ::info
  (s/keys :opt [::accessor
                ::client-token
                ::display-name
                ::lease-duration
                ::policies
                ::orphan?
                ::renewable?
                ::renew-after
                ::created-at
                ::expires-at]))


;; ## General Functions

(defn expires-within?
  "True if the auth will expire within `ttl` seconds."
  [auth ttl]
  (if-let [expires-at (::expires-at auth)]
    (-> (u/now)
        (.plusSeconds ttl)
        (.isAfter expires-at))
    false))


(defn expired?
  "True if the auth is expired."
  [auth]
  (expires-within? auth 0))


(defn renewable?
  "True if the auth token can be renewed right now."
  [auth]
  (and (::renewable? auth)
       (::expires-at auth)
       (if-let [gate (::renew-after auth)]
         (.isAfter (u/now) gate)
         true)
       (not (expired? auth))))


;; ## Maintenance Logic

(defn maintain!
  "Maintain an auth token as part of a timer."
  [f auth]
  (try
    (cond
      (expired? auth)
      :expired

      (and (renewable? auth)
           (expires-within? auth 60))
      (do (f auth) :renewed)

      :else
      :current)
    (catch Exception ex
      (log/error ex "Failed to renew Vault authentication" (ex-message ex))
      :error)))
