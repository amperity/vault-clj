(ns vault.auth
  "High-level namespace for client authentication."
  (:require
    [clojure.tools.logging :as log]
    [vault.util :as u]))


;; ## Data Specs

(def ^:private auth-spec
  "Specification for authentication data maps."
  {;; Authentication token string.
   ::token string?

   ;; Token accessor id string.
   ::accessor string?

   ;; Display name for the token.
   ::display-name string?

   ;; Number of seconds the token lease is valid for.
   ::lease-duration nat-int?

   ;; Set of policies applied to the token.
   ::policies
   (fn valid-policies?
     [policies]
     (and (set? policies) (every? string? policies)))

   ;; Whether the token is an orphan.
   ::orphan? boolean?

   ;; Whether the token is renewable.
   ::renewable? boolean?

   ;; Instant after which the token can be renewed again.
   ::renew-after inst?

   ;; Instant the token was created.
   ::created-at inst?

   ;; Instant the token expires.
   ::expires-at inst?})


(defn valid?
  "True if the auth information map conforms to the spec."
  [auth]
  (u/validate auth-spec auth))


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
       (not (expired? auth))))


;; ## State Maintenance

(defn new-state
  "Construct a new auth state atom."
  []
  (atom {} :validator valid?))


(defn maintain!
  "Maintain authentication state. Returns a keyword indicating the maintenance
  result."
  [state f]
  (let [renew-within 60
        renew-backoff 60
        auth @state]
    (try
      (cond
        (expired? auth)
        :expired

        (and (renewable? auth)
             (expires-within? auth renew-within)
             (if-let [renew-after (::renew-after auth)]
               (.isAfter (u/now) renew-after)
               true))
        (do
          (f)
          (swap! state assoc ::renew-after (.plusSeconds (u/now) renew-backoff))
          :renewed)

        :else
        :current)
      (catch Exception ex
        (log/error ex "Failed to maintain Vault authentication" (ex-message ex))
        :error))))
