(ns vault.sys.wrapping-test
  (:require
    [clojure.test :refer [are is testing deftest]]
    [vault.integration :refer [with-dev-server]]
    [vault.sys.wrapping :as sys.wrapping]))


(deftest ^:integration http-api
  (with-dev-server
    (testing "wrap data"
      (testing "integer TTL"
        (let [result (sys.wrapping/wrap client {:foo "bar" :baz "buzz"} 30)]
          (is (= 30 (:ttl result)))
          (is (string? (:token result)))))
      (testing "string TTL"
        (is (= 60 (:ttl (sys.wrapping/wrap client {:foo "bar"} "60s"))))
        (is (= 300 (:ttl (sys.wrapping/wrap client {:foo "bar"} "5m")))))
      (testing "invalid data type"
        (are [data] (thrown-with-msg? IllegalArgumentException #"Data to wrap must be a map"
                      (sys.wrapping/wrap client data 30))
          "hunter2"
          [1 2 3])))
    (testing "lookup token"
      (let [wrap-info (sys.wrapping/wrap client {:foo "bar"} 60)
            result (sys.wrapping/lookup client (:token wrap-info))]
        (is (= (:creation-path wrap-info) (:creation-path result)))
        (is (= (:creation-time wrap-info) (:creation-time result)))
        (is (= (:ttl wrap-info) (:creation-ttl result)))))
    (testing "rewrap token"
      (let [wrap-info (sys.wrapping/wrap client {:foo "bar"} 60)]
        (is (= (:creation-time wrap-info)
               (:creation-time (sys.wrapping/lookup client (:token wrap-info)))))
        (let [rewrapped-info (sys.wrapping/rewrap client (:token wrap-info))
              rewrapped-lookup (sys.wrapping/lookup client (:token rewrapped-info))]
          (is (thrown? Exception (sys.wrapping/lookup client (:token wrap-info)))
              "original token should no longer exist")
          (is (= (:ttl wrap-info)
                 (:ttl rewrapped-info)
                 (:creation-ttl rewrapped-lookup))))))
    (testing "unwrap token"
      (let [wrap-info (sys.wrapping/wrap client {:foo "bar" :baz "buzz"} 60)
            result (sys.wrapping/unwrap client (:token wrap-info))]
        (is (= {:foo "bar" :baz "buzz"} result))))))
