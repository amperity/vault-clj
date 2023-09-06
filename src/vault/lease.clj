(ns vault.lease
  "High-level namespace for tracking and maintaining leases on dynamic secrets
  read by a vault client."
  (:require
    [clojure.tools.logging :as log]
    [vault.util :as u])
  (:import
    clojure.lang.Agent
    java.util.concurrent.ExecutorService))


;; ## Data Specs

(def ^:private lease-spec
  "Specification for lease data maps."
  {;; Unique lease identifier.
   ::id string?

   ;; A cache lookup key for identifying this lease to future calls.
   ::key some?

   ;; How long the lease is valid for, in seconds.
   ::duration nat-int?

   ;; Instant in time the lease expires at.
   ::expires-at inst?

   ;; Secret data map.
   ::data map?

   ;; Can this lease be renewed to extend its validity?
   ::renewable? boolean?

   ;; How many seconds to attempt to add to the lease duration when renewing.
   ::renew-increment pos-int?

   ;; Try to renew this lease when the current time is within this many seconds of
   ;; the `expires-at` deadline.
   ::renew-within pos-int?

   ;; Wait at least this many seconds between successful renewals of this lease.
   ::renew-backoff nat-int?

   ;; Time after which this lease can be attempted to be renewed.
   ::renew-after inst?

   ;; A no-argument function to call to rotate this lease. This should return true
   ;; if the rotation succeeded, else false.
   ::rotate-fn fn?

   ;; Try to read a new secret when the current time is within this many seconds
   ;; of the `expires-at` deadline.
   ::rotate-within nat-int?

   ;; Function to call with lease info after a successful renewal.
   ;; - :client
   ;; - :lease
   ;; - :data
   ::on-renew fn?

   ;; Function to call with lease info after a successful rotation.
   ;; - :client
   ;; - :lease
   ;; - :data
   ::on-rotate fn?


   ;; Function to call with any exceptions thrown during periodic maintenance.
   ;; - :client
   ;; - :lease
   ;; - :data
   ;; - :error
   ::on-error fn?})


(defn valid?
  "True if the lease information map conforms to the spec."
  [lease]
  (u/validate lease-spec lease))


;; ## General Functions

(defn expires-within?
  "True if the lease will expire within `ttl` seconds."
  [lease ttl]
  (let [expires-at (::expires-at lease)]
    (or (nil? expires-at)
        (-> (u/now)
            (.plusSeconds ttl)
            (.isBefore expires-at)
            (not)))))


(defn expired?
  "True if the given lease is expired."
  [lease]
  (expires-within? lease 0))


(defn renewable-lease
  "Helper to apply common renewal settings to the lease map.

  Options may contain:

  - `:renew?`
    If true, attempt to automatically renew the lease when near expiry.
    (Default: false)
  - `:renew-within`
    Renew the lease when within this many seconds of the lease expiry.
    (Default: 60)
  - `:renew-increment`
    How long to request the lease be renewed for, in seconds.
  - `:on-renew`
    A function to call with the updated lease information after a successful
    renewal.
  - `:on-error`
    A function to call with any exceptions encountered while renewing or
    rotating the lease."
  [lease opts]
  (if (and (:renew? opts) (::renewable? lease))
    (-> lease
        (assoc ::renew-within (:renew-within opts 60))
        (cond->
          (:renew-increment opts)
          (assoc ::renew-increment (:renew-increment opts))

          (:on-renew opts)
          (assoc ::on-renew (:on-renew opts))

          (:on-error opts)
          (assoc ::on-error (:on-error opts))))
    lease))


(defn rotatable-lease
  "Helper to apply common rotation settings to the lease map. The rotation
  function will be called with no arguments and should synchronously return
  a new secret data result, and update the lease store as a side-effect.

  Options may contain:

  - `:rotate?`
    If true, attempt to read a new secret when the lease can no longer be
    renewed. (Default: false)
  - `:rotate-within`
    Rotate the secret when within this many seconds of the lease expiry.
    (Default: 60)
  - `:on-rotate`
    A function to call with the new secret data after a successful rotation.
  - `:on-error`
    A function to call with any exceptions encountered while renewing or
    rotating the lease."
  [lease opts rotate-fn]
  (when-not rotate-fn
    (throw (IllegalArgumentException.
             "Can't make a lease rotatable with no rotation function")))
  (if (:rotate? opts)
    (-> lease
        (assoc ::rotate-fn rotate-fn
               ::rotate-within (:rotate-within opts 60))
        (cond->
          (:on-rotate opts)
          (assoc ::on-rotate (:on-rotate opts))

          (:on-error opts)
          (assoc ::on-error (:on-error opts))))
    lease))


;; ## Lease Tracking

(defn- valid-store?
  "Checks a store state for validity."
  [state]
  (every?
    (fn valid-entry?
      [[id info]]
      (and (string? id) (valid? info)))
    state))


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
  secret data, or nil if not found or expired."
  [store cache-key]
  (let [lease (first (filter (comp #{cache-key} ::key) (vals @store)))
        data (::data lease)]
    (when (and data (not (expired? lease)))
      (vary-meta data merge (dissoc lease ::data)))))


(defn put!
  "Persist a leased secret in the store. Returns the lease data."
  [store lease data]
  (when-not (expired? lease)
    (swap! store assoc (::id lease) (assoc lease ::data data)))
  (vary-meta data merge lease))


(defn update!
  "Merge some updated information into an existing lease. Updates should
  contain a `::lease/id`. Returns the updated lease, or nil if no such lease
  was present."
  [store updates]
  (let [lease-id (::id updates)]
    (-> store
        (swap! u/update-some lease-id merge updates)
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


;; ## Maintenance Logic

(defn- renew?
  "True if the lease should be renewed."
  [lease]
  (and (::renewable? lease)
       (expires-within? lease (::renew-within lease 0))
       (not (expired? lease))
       (if-let [gate (::renew-after lease)]
         (.isAfter (u/now) gate)
         true)))


(defn- rotate?
  "True if the lease should be rotated."
  [lease]
  (and (::rotate-fn lease)
       (expires-within? lease (::rotate-within lease 0))))


(defn- invoke-callback
  "Invoke a callback function with the lease information."
  ([cb-key client lease]
   (invoke-callback cb-key client lease nil nil))
  ([cb-key client lease data]
   (invoke-callback cb-key client lease data nil))
  ([cb-key client lease data error]
   (when-let [callback (get lease cb-key)]
     (let [executor (or (:callback-executor client)
                        Agent/soloExecutor)
           runnable #(callback
                       {:client client
                        :lease (dissoc lease ::data)
                        :data (or data (::data lease))
                        :error error})]
       (.submit ^ExecutorService executor ^Runnable runnable)))))


(defn- renew!
  "Attempt to renew the lease, handling callbacks. Returns true if the renewal
  succeeded, false if not. The renewal function will be called with the lease
  and should synchronously return updated lease info. The lease store should be
  updated as a side-effect."
  [client lease renew-fn]
  (try
    (let [result (renew-fn lease)]
      (invoke-callback ::on-renew client result)
      true)
    (catch Exception ex
      (invoke-callback ::on-error client lease nil ex)
      false)))


(defn- rotate!
  "Attempt to rotate a secret, handling callbacks. Returns true if the rotation
  succeeded, false if not. The rotation function will be called with no
  arguments and should synchronously return a result or throw an error. The
  lease store should be updated as a side-effect."
  [client lease]
  (try
    (let [rotate-fn (::rotate-fn lease)
          result (rotate-fn)]
      (invoke-callback ::on-rotate client lease result)
      true)
    (catch Exception ex
      (invoke-callback ::on-error client lease nil ex)
      false)))


(defn- maintain-lease!
  "Maintain a single secret lease as appropriate. Returns a keyword indicating
  the action and final state of the lease."
  [client lease renew-fn]
  (try
    (cond
      (renew? lease)
      (if (renew! client lease renew-fn)
        :renew-ok
        :renew-fail)

      (rotate? lease)
      (if (rotate! client lease)
        :rotate-ok
        :rotate-fail)

      (expired? lease)
      :expired

      :else
      :active)
    (catch Exception ex
      (log/error ex "Unhandled error while maintaining lease" (::id lease))
      (invoke-callback ::on-error client lease nil ex)
      :error)))


(defn maintain!
  "Maintain all the leases in the store, blocking until complete."
  [client renew-fn]
  (when-let [store (:leases client)]
    (doseq [[lease-id lease] @store]
      (case (maintain-lease! client lease renew-fn)
        ;; After successful renewal, set a backoff before we try to renew again.
        :renew-ok
        (let [after (.plusSeconds (u/now) (::renew-backoff lease 60))]
          (swap! store assoc-in [lease-id ::renew-after] after))

        ;; After rotating, remove the old lease.
        :rotate-ok
        (swap! store dissoc lease-id)

        ;; Remove expired leases.
        :expired
        (swap! store dissoc lease-id)

        ;; In other cases, there's no action to take.
        nil))))
