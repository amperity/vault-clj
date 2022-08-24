(ns vault.lease
  "High-level namespace for tracking and maintaining leases on dynamic secrets
  read by a vault client."
  (:require
    [clojure.spec.alpha :as s]
    [clojure.tools.logging :as log]
    [vault.util :as u]))


;; ## Data Specs

;; Unique lease identifier.
(s/def ::id string?)


;; How long the lease is valid for, in seconds.
(s/def ::duration nat-int?)


;; Instant in time the lease expires at.
(s/def ::expires-at inst?)


;; Secret data map.
(s/def ::data map?)


;; A no-argument function to call to renew this lease. This should return the
;; updated lease information, or nil on error.
(s/def ::renew! fn?)


;; Can this lease be renewed to extend its validity?
(s/def ::renewable? boolean?)


;; Try to renew this lease when the current time is within this many seconds of
;; the `expires-at` deadline.
(s/def ::renew-within nat-int?)


;; A no-argument function to call to rotate this lease. This should return true
;; if the rotation succeeded, else false.
(s/def ::rotate! fn?)


;; Try to read a new secret when the current time is within this many seconds
;; of the `expires-at` deadline.
(s/def ::rotate-within nat-int?)


;; Full lease information map.
(s/def ::info
  (s/keys :opt [::id
                ::duration
                ::expires-at
                ::data
                ::renew!
                ::renewable?
                ::renew-within
                ::rotate!
                ::rotate-within]))


;; ## Lease Functions

(defn expires-within?
  "True if the lease will expires within `ttl` seconds."
  [lease ttl]
  (let [expires-at (::expires-at lease)]
    (or (nil? expires-at)
        (-> (u/now)
            (.plusSeconds ttl)
            (.isAfter expires-at)))))


(defn expired?
  "True if the given lease is expired."
  [lease]
  (expires-within? lease 0))


;; ## Lease Store

(s/def ::store
  (s/map-of vector? ::info))


(defn- valid-store?
  "Checks a store state for validity."
  [state]
  (s/valid? ::store state))


(defn new-store
  "Construct a new stateful store for leased secrets."
  []
  (atom {} :validator valid-store?))


(defn get-lease
  "Retrieve a cached lease from the store. Returns the lease information,
  including secret data, or nil if not found."
  [store cache-key]
  (when-let [lease (get @store cache-key)]
    (when-not (expired? lease)
      lease)))


(defn get-data
  "Retrieve an existing leased secret from the store. Returns the secret data,
  or nil if not found."
  [store cache-key]
  (let [lease (get-lease store cache-key)
        data (::data lease)]
    (when (and lease data)
      (vary-meta data merge (dissoc lease ::data)))))


(defn put!
  "Persist a leased secret in the store."
  [store cache-key lease data]
  (when-not (expired? lease)
    (swap! store assoc cache-key (assoc lease ::data data)))
  data)


(defn invalidate!
  "Remove an entry for the given cache key, if present."
  [store cache-key]
  (swap! store dissoc cache-key)
  nil)


(defn prune!
  "Remove expired leases from the store."
  [store]
  (swap! store
         (fn remove-expired
           [leases]
           (into (empty leases)
                 (remove (comp expired? val))
                 leases)))
  nil)
