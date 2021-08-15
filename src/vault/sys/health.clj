(ns vault.sys.health
  (:require
    [vault.client.http :as http]
    [vault.client.mock :as mock]
    [vault.client.util :as u])
  (:import
    vault.client.http.HTTPClient
    vault.client.mock.MockClient))


;; ## API Protocol

(defprotocol HealthAPI
  "The health endpoint is used to check the health status of Vault."

  (read-health
    [client params]
    "This endpoint returns the health status of Vault."))


;; ## HTTP Client

(extend-type HTTPClient

  HealthAPI

  (read-health
    [client _params]
    ;; TODO: call /sys/health
    ,,,))


;; ## Mock Client

(extend-type MockClient

  HealthAPI

  (read-health
    [_ _params]
    (deliver (promise)
             {:cluster-id "01234567-89ab-cdef-0123-456789abcdef"
              :cluster-name "vault-cluster-mock"
              :version "0.0.0"
              :initialized true
              :sealed false
              :standby false
              :performance-standby false
              :replication-perf-mode "disabled"
              :replication-dr-mode "disabled"
              :server-time-utc (.toEpochMilli (u/now))})))
