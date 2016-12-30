(ns vault.store
  "Storage logic for Vault secrets and their associated leases."
  (:require
    [clojure.string :as str]
    [clojure.tools.logging :as log]))

; Generic secret backend responses look like:
; {
;   "lease_id": "",
;   "renewable": false,
;   "lease_duration": 2592000,
;   "data": { ... },
;   "wrap_info": null,
;   "warnings": null,
;   "auth": null
; }

; Dynamic secret backend responses look like:
; {
;   "request_id": "9b777b8c-20ab-49da-413a-cfc4aa8704c5",
;   "lease_id": "vagrant/service-db/creds/tenant-service/3c21206e-ab6d-1911-c4d8-d6ad439dff03",
;   "renewable": true,
;   "lease_duration": 900,
;   "data": {
;     "password": "a01a247c-57c8-8525-6596-ffd8bf824218",
;     "username": "root-9847e3b8-dd2d-0417-51e1-f6788f1fbbc6"
;   },
;   "wrap_info": null,
;   "warnings": null,
;   "auth": null
; }

; Renewal responses look like:
; {
;   "request_id": "765eeb84-e9fb-31d6-72f3-0c1dc60f7389",
;   "lease_id": "vagrant/service-db/creds/tenant-service/3c21206e-ab6d-1911-c4d8-d6ad439dff03",
;   "renewable": true,
;   "lease_duration": 900,
;   "data": null,
;   "wrap_info": null,
;   "warnings": null,
;   "auth": null
; }


; In memory secret leases look like:
#_
{:lease-id String
 :lease-duration Long ; seconds
 :renewable Boolean
 :data {Keyword String}
 :watchers [Fn]}



;; ## Lease Management

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
    (let [secret {:lease-id (:lease_id info)
                  :lease-duration (:lease_duration info)
                  :renewable (boolean (:renewable info))
                  :expiry (+ (now) (* (:lease_duration info 60) 1000))
                  :data (:data info)}]
      (swap! store assoc path secret)
      secret)))


(defn invalidate!
  "Removes a secret from the store."
  [store path]
  (swap! store dissoc path)
  nil)


(defn renew-leases!
  "Renews all the secrets within `renewal-window` seconds of expiring. Updates
  the lease store and invokes any registered lease callbacks."
  [renewal-fn store renewal-window]
  (doseq [[path secret] (filter #(and (renewable? (val %))
                                      (expires-within? (val %) renewal-window))
                                @store)]
    (try
      (let [result (renewal-fn (:lease-id secret))]
        (clojure.pprint/pprint result))
      (catch Exception ex
        (log/error ex "Failed to renew secret lease" (:lease-id secret))))))
