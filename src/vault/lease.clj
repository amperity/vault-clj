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


;; A cache lookup key for identifying this lease to future calls.
(s/def ::key some?)


;; How long the lease is valid for, in seconds.
(s/def ::duration nat-int?)


;; Instant in time the lease expires at.
(s/def ::expires-at inst?)


;; Secret data map.
(s/def ::data map?)


;; A no-argument function to call to renew this lease. This should update the
;; client's lease store directly.
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
                ::key
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
  "True if the lease will expire within `ttl` seconds."
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


(defn renewable?
  "True if the given lease is a valid renewal target."
  [lease]
  (and (::renew! lease)
       (::renewable? lease)
       (::expires-at lease)
       (not (expired? lease))
       (expires-within? lease (::renew-within lease 60))))


(defn rotatable?
  "True if the given lease is a valid rotation target."
  [lease]
  (and (::rotate! lease)
       (::expires-at lease)
       (expires-within? lease (::rotate-within lease 60))))


;; ## Lease Store

(s/def ::store
  (s/map-of ::id ::info))


(defn- valid-store?
  "Checks a store state for validity."
  [state]
  (s/valid? ::store state))


(defn new-store
  "Construct a new stateful store for leased secrets."
  []
  (atom {} :validator valid-store?))


(defn get-lease
  "Retrieve a lease from the store. Returns the lease information, including
  secret data, or nil if not found or expired."
  [store lease-id]
  (when-let [lease (get @store lease-id)]
    (when-not (expired? lease)
      lease)))


(defn find-data
  "Retrieve an existing leased secret from the store by cache key. Returns the
  secret data, or nil if not found."
  [store cache-key]
  (let [lease (first (filter (comp #{cache-key} ::key) (vals @store)))
        data (::data lease)]
    (when (and lease data)
      (vary-meta data merge (dissoc lease ::data)))))


(defn put!
  "Persist a leased secret in the store. Returns the lease data."
  [store lease data]
  (when-not (expired? lease)
    (swap! store assoc (::id lease) (assoc lease ::data data)))
  (vary-meta data merge lease))


(defn update!
  "Merge some updated information into an existing lease. Updates should
  contain a `::lease/id`. Returns the updated lease."
  [store updates]
  (let [lease-id (::id updates)]
    (-> store
        (swap! update
               lease-id
               (fn update-lease
                 [lease]
                 (-> lease
                     (merge updates)
                     (vary-meta merge (meta updates)))))
        (get lease-id))))


(defn delete!
  "Remove an entry for the given lease, if present."
  [store lease-id]
  (swap! store dissoc lease-id)
  nil)


(defn invalidate!
  "Remove entries matching the given cache key."
  [store cache-key]
  (swap! store (fn remove-keys
                 [leases]
                 (into (empty leases)
                       (remove (comp #{cache-key} ::key val))
                       leases)))
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


;; ## Timer Logic

(defn maintain!
  "Maintain a single secret lease as appropriate. Returns true if the lease
  should be kept, false if it should be removed."
  [lease]
  (try
    (cond
      (renewable? lease)
      (let [renew! (::renew! lease)]
        (renew!)
        true)

      (rotatable? lease)
      (let [rotate! (::rotate! lease)]
        (not (rotate!)))

      (expired? lease)
      false

      :else
      true)
    (catch Exception ex
      (log/error ex "Unhandled error while maintaining lease" (::id lease))
      ;; Leave original lease to retry.
      ;; TODO: failure backoff?
      lease)))


(defn maintain-leases!
  "Maintain all the leases in the store, blocking until complete."
  [store]
  (doseq [[lease-id lease] @store]
    (when-not (maintain! lease)
      (swap! store dissoc lease-id))))
