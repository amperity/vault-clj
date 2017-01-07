(ns vault.lease
  "Storage logic for Vault secrets and their associated leases."
  (:require
    [clojure.string :as str]
    [clojure.tools.logging :as log])
  (:import
    java.time.Instant))


(defn- now
  "Helper method to get the current time in epoch milliseconds."
  ^java.time.Instant
  []
  (Instant/now))



;; ## Lease Construction

(defn auth-lease
  "Adds extra fields and sanitizes an authentication lease."
  [auth]
  (if-let [duration (:lease-duration auth)]
    (assoc auth ::expiry (.plusSeconds (now) duration))
    auth))


(defn secret-lease
  "Adds extra fields and cleans up the secret lease info."
  [info]
  (cond->
    {:lease-id (when-not (str/blank? (:lease-id info))
                 (:lease-id info))
     :lease-duration (:lease-duration info)
     :renewable (boolean (:renewable info))
     ::expiry (.plusSeconds (now) (:lease-duration info 60))}
    (:path info)
      (assoc :path (:path info))
    (:data info)
      (assoc :data (:data info)
             ::issued (now))
    (some? (:renew info))
      (assoc ::renew (boolean (:renew info)))
    (some? (:rotate info))
      (assoc ::rotate (boolean (:rotate info)))))



;; ## Lease Logic

(defn leased?
  "Determines whether the secret is leased."
  [secret]
  (not (str/blank? (:lease-id secret))))


(defn renewable?
  "Determines whether a leased lease is renewable."
  [secret]
  (and (leased? secret) (:renewable secret)))


(defn expires-within?
  "Determines whether the lease expires within the given number of seconds."
  [lease duration]
  (if-let [expiry (::expiry lease)]
    (-> (now)
        (.plusSeconds duration)
        (.isAfter expiry))
    false))


(defn expired?
  "Determines whether the lease has expired."
  [lease]
  (expires-within? lease 0))



;; ## Secret Storage

(defn new-store
  "Creates a new stateful store for leased secrets.

  This takes the form of a reference map of secret paths to lease data,
  including the secret data and any registered callbacks."
  []
  (atom {}))


(defn list-leases
  "Returns a list of lease information currently stored."
  [store]
  (mapv (fn [[k v]] (-> v (dissoc :data) (assoc :path k)))
        @store))


(defn lookup
  "Looks up the given secret path in the store by path or lease-id. Returns the
  lease data, if present."
  [store path-or-id]
  (let [leases @store]
    (or (get leases path-or-id)
        (some->>
          (vals leases)
          (filter #(= path-or-id (:lease-id %)))
          (first)))))


(defn update!
  "Updates secret lease information in the store."
  [store info]
  (when info
    (if-let [path (or (:path info)
                      (some->>
                        @store
                        (filter #(= (:lease-id info) (:lease-id (val %))))
                        (first)
                        (key)))]
      (get (swap! store update path merge (secret-lease info)) path)
      (log/error "Cannot update lease with no matching store entry:" (dissoc info :data)))))


(defn remove-path!
  "Removes a lease from the store by path."
  [store path]
  (swap! store dissoc path)
  nil)


(defn remove-lease!
  "Removes a lease from the store by id."
  [store lease-id]
  (swap! store (fn [data] (into {} (remove #(= lease-id (:lease-id (val %))) data))))
  nil)


(defn sweep!
  "Removes expired leases from the store."
  [store]
  (when-let [expired (seq (filter (comp expired? val) @store))]
    (log/warn "Expiring leased secrets:" (str/join \space (map key expired)))
    (apply swap! store dissoc (map key expired))
    store))


(defn renewable-leases
  "Returns a sequence of leases which are within `window` seconds of expiring,
  are renewable, and are marked for renewal."
  [store window]
  (->> (list-leases store)
       (filter ::renew)
       (filter renewable?)
       (filter #(expires-within? % window))))


(defn rotatable-leases
  "Returns a sequence of leases which are within `window` seconds of expiring,
  are not renewable, and are marked for rotation."
  [store window]
  (->> (list-leases store)
       (filter ::rotate)
       (remove renewable?)
       (filter #(expires-within? % window))))


(defn lease-watcher
  "Constructs a watch function which will call the given function with the
  secret info at a given path when the lease changes."
  [path watch-fn]
  (fn watch
    [_ _ old-state new-state]
    (let [old-info (get old-state path)
          new-info (get new-state path)]
      (when (not= (:lease-id old-info)
                  (:lease-id new-info))
        (watch-fn new-info)))))
