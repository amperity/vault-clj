(ns vault.sys.health
  (:require
    [vault.client.http :as http]
    [vault.client.mock :as mock]
    [vault.client.response :as resp]
    [vault.client.util :as u])
  (:import
    vault.client.http.HTTPClient
    vault.client.mock.MockClient))


;; ## API Protocol

(defprotocol HealthAPI
  "The health endpoint is used to check the health status of Vault."

  (read-health
    [client params]
    "This endpoint returns the health status of Vault.

    https://www.vaultproject.io/api-docs/system/health#read-health-information"))


;; ## HTTP Client

(extend-type HTTPClient

  HealthAPI

  (read-health
    [client params]
    (http/call-api
      client :get "sys/health"
      {:query-params params})))


;; ## Mock Client

(extend-type MockClient

  HealthAPI

  (read-health
    [client _params]
    (mock/success-response
      client
      {:cluster-id "01234567-89ab-cdef-0123-456789abcdef"
       :cluster-name "vault-cluster-mock"
       :version "0.0.0"
       :initialized true
       :sealed false
       :standby false
       :performance-standby false
       :replication-perf-mode "disabled"
       :replication-dr-mode "disabled"
       :server-time-utc (u/now-milli)})))
