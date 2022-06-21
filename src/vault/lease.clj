(ns vault.lease
  "High-level namespace for tracking and maintaining leases on dynamic secrets
  read by a vault client."
  (:require
    [vault.util :as u])
  (:import
    java.time.Instant))


;; TODO:
;; - implementing namespaces need a way of registering new leases with the state
;; - lease code needs a way to callback to renew the lease, and another callback to rotate it
;; - external callers need a hook (or "watch") for events on a particular dynamic secret
;; - someone needs to know how to start a timer thread that does this maintenance


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

(defn new-store
  "Construct a new stateful store for leased secrets."
  []
  (atom {}))


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
