(ns vault.cache
  "Caching logic for Vault secrets and their associated leases."
  (:require
    [clojure.string :as str]
    [clojure.tools.logging :as log]))


(defn new-cache
  "Creates a new stateful cache store for secrets."
  []
  (atom {}))


(defn sweep!
  "Removes expired secrets from the cache. Returns the updated cache data."
  [cache]
  (swap!
    cache
    (fn clean [data]
      (let [now (System/currentTimeMillis)
            expired (->> data
                         (filter #(<= (:expiry (val %)) now))
                         (map key))]
        (log/warn "Expiring leased secrets:" (str/join \space expired))
        (apply dissoc data expired)))))


(defn lookup
  "Looks up the given key in the cache. Returns the secret data, if present and
  not expired. May modify the cache by dropping expired secrets."
  [cache path]
  (get (sweep! cache) path))


(defn store!
  "Stores the given secret data in the cache. Returns the stored secret data.
  If the secret has no `lease_duration` a default of 60 seconds is used."
  [cache path secret]
  (when (get secret "data")
    (let [info {:lease-id (:lease_id secret)
                :lease-duration (:lease_duration secret)
                :renewable (:renewable secret)
                :expiry (+ (System/currentTimeMillis)
                           (* (:lease_duration secret 60) 1000))
                :data (:data secret)}]
      (swap! cache assoc path info)
      info)))


(defn invalidate!
  "Deletes a secret from the cache."
  [cache path]
  (swap! cache dissoc path))
