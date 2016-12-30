(ns vault.store
  "Storage logic for Vault secrets and their associated leases."
  (:require
    [clojure.string :as str]
    [clojure.tools.logging :as log]))


(defn- now
  "Helper method to get the current time in epoch milliseconds."
  []
  (System/currentTimeMillis))


(defn renewable?
  "Determines whether a leased secret is renewable."
  [secret]
  (and (:renewable secret) (not (str/blank? (:lease-id secret)))))


(defn expires-within?
  "Determines whether the secret expires within the given number of seconds."
  [secret duration]
  (<= (- (:expiry secret) duration) (now)))


(defn expired?
  "Determines whether the secret has expired."
  [secret]
  (expires-within? secret 0))



;; ## Secret Storage

(defn new-store
  "Creates a new stateful store for leased secrets.

  This takes the form of a reference map of secret paths to lease data,
  including the secret data and any registered callbacks."
  []
  (atom {}))


(defn sweep!
  "Removes expired secrets from the store. Returns the updated store data."
  [store]
  (swap!
    store
    (fn clean [data]
      (let [expired (map key (filter (comp expired? val) data))]
        (when (seq expired)
          (log/warn "Expiring leased secrets:" (str/join \space expired)))
        (apply dissoc data expired)))))


(defn lookup
  "Looks up the given secret path in the store. Returns the lease data, if
  present and not expired. May modify the store by dropping expired secrets."
  [store path]
  (get (sweep! store) path))


(defn store!
  "Stores leased secret data in the store. Returns the stored secret data."
  [store path info]
  (when (:data info)
    (let [secret {:lease-id (:lease_id response)
                  :lease-duration (:lease_duration response)
                  :renewable (boolean (:renewable response))
                  :expiry (+ (now) (* (:lease_duration response 60) 1000))
                  :data (:data response)}]
      (swap! store assoc path secret)
      secret)))


(defn invalidate!
  "Removes a secret from the store."
  [store path]
  (swap! store dissoc path)
  nil)
