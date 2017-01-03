(ns vault.lease
  "Storage logic for Vault secrets and their associated leases."
  (:require
    [clojure.string :as str]
    [clojure.tools.logging :as log])
  (:import
    java.time.Instant))

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
;   "data": { ... },
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
{:data {String String}
 :lease-id String
 :lease-duration Long ; seconds
 :renewable Boolean
 ::rotate Boolean ; whether to re-read credentials on expiry
 ::issued Instant
 ::expiry Instant
 ::watchers [Fn]}



;; ## Lease Management

(defn- now
  "Helper method to get the current time in epoch milliseconds."
  ^java.time.Instant
  []
  (Instant/now))


(defn renewable?
  "Determines whether a leased secret is renewable."
  [secret]
  (and (:renewable secret) (not (str/blank? (:lease-id secret)))))


(defn rotate?
  "Determines whether a leased secret should be rotated on expiration."
  [secret]
  (::rotate secret))


(defn expires-within?
  "Determines whether the secret expires within the given number of seconds."
  [secret duration]
  (-> (now)
      (.plusSeconds duration)
      (.isAfter (::expiry secret))))


(defn expired?
  "Determines whether the secret has expired."
  [secret]
  (expires-within? secret 0))


(defn- update-lease
  "Returns the secret map updated with new lease information."
  [secret info]
  (merge
    secret
    {:lease-id (:lease-id info)
     :lease-duration (:lease-duration info)
     :renewable (boolean (:renewable info))
     ::expiry (.plusSeconds (now) (:lease-duration info 60))}
    (when-not (::issued secret)
      {::issued (now)})
    (when-let [data (:data info)]
      {:data data})
    (when-let [watcher (:watch data)]
      (conj (::watchers secret []) watcher))))


(defn- find-secret
  "Locates a path/lease entry by the lease-id."
  [store lease-id]
  (first (filter (comp #{lease-id} :lease-id val) @store)))


(defn- call-watchers!
  [secret event]
  (run!
    #(try
       (% secret event)
       (catch Throwable t
         (log/error t "Error while calling secret lease watch function"
                    (.getName (class %)))))
    (::watchers secret)))



;; ## Secret Storage

(defn new-store
  "Creates a new stateful store for leased secrets.

  This takes the form of a reference map of secret paths to lease data,
  including the secret data and any registered callbacks."
  []
  (atom {}))


(defn lookup
  "Looks up the given secret path in the store. Returns the lease data, if
  present and not expired."
  [store path]
  (when-let [secret (get @store path)]
    (when-not (expired? secret)
      secret)))


(defn store!
  "Stores leased secret data in the store. Returns the stored secret data."
  [store path info]
  (when (:data info)
    (let [secret (update-lease nil info)]
      (swap! store assoc path secret)
      secret)))


(defn renew!
  "Updates leased secret information after renewal."
  [store info]
  (when-let [lease-id (:lease-id info)]
    (if-let [path (some-> (find-secret store lease-id) key)]
      (let [updated (get (swap! store update path update-lease info) path)]
        (call-watchers! updated :renew))
      (log/error "Cannot renew lease with no matching store entry:" lease-id))))


(defn rotate!
  "Updates leased secret information after secret rotation."
  [store path]
  (when-let [secret (get @store path)]
    (let [updated (get (swap! store update path update-lease info) path)]
      (call-watchers! updated :rotate))))


(defn invalidate!
  "Removes a secret from the store."
  [store path]
  (swap! store dissoc path)
  nil)


(defn sweep!
  "Removes expired secrets from the store."
  [store]
  (when-let [expired (seq (filter (comp expired? val) @data))]
    (log/warn "Expiring leased secrets:" (str/join \space (map key expired)))
    (apply swap! store dissoc data (map key expired))
    (run! #(call-watchers! (val %) :expire) expired)))
