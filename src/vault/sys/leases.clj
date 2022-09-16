(ns vault.sys.leases
  "The /sys/leases endpoint is used to used to view and manage leases in Vault.

  Reference: https://www.vaultproject.io/api-docs/system/leases"
  (:require
    [vault.client.http :as http]
    [vault.client.mock :as mock]
    [vault.lease :as lease]
    [vault.util :as u])
  (:import
    vault.client.http.HTTPClient
    vault.client.mock.MockClient))


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
    [client lease-id increment]
    "Renew a lease, requesting to extend the time it is valid for. The
    `increment` is a requested duration in seconds to extend the lease.")

  (revoke-lease!
    [client lease-id]
    "Revoke a lease, invalidating the secret it references."))


;; ## Mock Client

#_
(extend-type MockClient

  API

  ,,,)


;; ## HTTP Client

(extend-type HTTPClient

  API

  (read-lease
    [client lease-id]
    (http/call-api
      client :put "sys/leases/lookup"
      {:content-type :json
       :body {:lease_id lease-id}}))


  (list-leases
    [client prefix]
    (http/call-api
      client :list (u/join-path "sys/leases/lookup" prefix)
      {}))


  (renew-lease!
    [client lease-id increment]
    (http/call-api
      client :put "sys/leases/renew"
      {:content-type :json
       :body {:lease_id lease-id
              ;; TODO: is increment optional?
              :increment increment}
       :handle-response
       (fn handle-response
         [body]
         (when-let [lease (http/lease-info body)]
           (lease/update! (:leases client) lease)
           lease))}))


  (revoke-lease!
    [client lease-id]
    (lease/delete! (:leases client) lease-id)
    (http/call-api
      client :put "sys/leases/revoke"
      {:content-type :json
       :body {:lease_id lease-id}})))
