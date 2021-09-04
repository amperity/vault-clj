(ns vault.sys.health-test
  (:require
    [clojure.string :as str]
    [clojure.test :refer [is testing deftest]]
    [vault.client.mock :refer [mock-client]]
    [vault.integration :refer [with-dev-server]]
    [vault.sys.health :as sys.health]))


(deftest mock-api
  (let [client (mock-client)
        status (sys.health/read-health client {})]
    (is (str/starts-with? (:cluster-name status) "vault-cluster-"))
    (is (true? (:initialized status)))
    (is (false? (:standby status)))
    (is (false? (:sealed status)))
    (is (pos-int? (:server-time-utc status)))
    (is (string? (:version status)))))


(deftest ^:integration http-api
  (with-dev-server
    (testing "read-health"
      (let [status (sys.health/read-health client {})]
        (is (str/starts-with? (:cluster-name status) "vault-cluster-"))
        (is (true? (:initialized status)))
        (is (false? (:standby status)))
        (is (false? (:sealed status)))
        (is (pos-int? (:server-time-utc status)))
        (is (string? (:version status)))))))
