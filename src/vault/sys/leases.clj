(ns vault.sys.leases
  "The `/sys/leases` endpoint is used to view and manage leases in Vault.

  Reference: https://www.vaultproject.io/api-docs/system/leases"
  (:require
    [vault.client.http :as http]
    [vault.lease :as lease]
    [vault.util :as u])
  (:import
    vault.client.http.HTTPClient))


;; ## API Protocol

(defprotocol API
  "The leases endpoint is used to manage secret leases in Vault."

  (read-lease
    [client lease-id]
    "Retrieve lease metadata.")

  (list-leases
    [client prefix]
    "Return a collection of lease ids under the given prefix. This endpoint
    requires sudo capability.")

  (renew-lease!
    [client lease-id]
    [client lease-id increment]
    "Renew a lease, requesting to extend the time it is valid for. The
    `increment` is a requested duration in seconds to extend the lease.")

  (revoke-lease!
    [client lease-id]
    "Revoke a lease, invalidating the secret it references."))


;; ## HTTP Client

(extend-type HTTPClient

  API

  (read-lease
    [client lease-id]
    (http/call-api
      client ::read-lease
      :put "sys/leases/lookup"
      {:info {::lease/id lease-id}
       :content-type :json
       :body {:lease_id lease-id}}))


  (list-leases
    [client prefix]
    (http/call-api
      client ::list-leases
      :list (u/join-path "sys/leases/lookup" prefix)
      {:info {::prefix prefix}}))


  (renew-lease!
    ([client lease-id]
     (renew-lease! client lease-id nil))
    ([client lease-id increment]
     (http/call-api
       client ::renew-lease!
       :put "sys/leases/renew"
       {:info {::lease/id lease-id}
        :content-type :json
        :body (cond-> {:lease_id lease-id}
                increment
                (assoc :increment increment))
        :handle-response http/lease-info
        :on-success (fn update-lease
                      [lease]
                      (lease/update! client lease))})))


  (revoke-lease!
    [client lease-id]
    (lease/delete! client lease-id)
    (http/call-api
      client ::revoke-lease!
      :put "sys/leases/revoke"
      {:info {::lease/id lease-id}
       :content-type :json
       :body {:lease_id lease-id}})))
