(ns vault.client.http-test
  (:require
    [clojure.test :refer [deftest testing is]]
    [vault.client :as vault]
    [vault.client.http :as http]))


(deftest call-api
  (testing "bad arguments"
    (is (thrown-with-msg? IllegalArgumentException #"call on nil client"
          (http/call-api nil :get "sys/health" {})))
    (is (thrown-with-msg? IllegalArgumentException #"call without keyword method"
          (http/call-api {} nil "sys/health" {})))
    (is (thrown-with-msg? IllegalArgumentException #"call on blank path"
          (http/call-api {} :get "" {}))))
  ,,,)


(deftest timer-logic
  (testing "with normal looping"
    (let [calls (atom 0)
          thread (#'http/start-timer!
                  "test-timer"
                  #(swap! calls inc)
                  10 2)]
      (is (true? (.isAlive thread)))
      (Thread/sleep 50)
      (#'http/stop-timer! thread)
      (is (false? (.isAlive thread)))
      (is (<= 4 @calls 6))))
  (testing "with sleepy handler"
    (let [calls (atom 0)
          thread (#'http/start-timer!
                  "test-timer"
                  #(do (Thread/sleep 100)
                       (swap! calls inc))
                  1 0)]
      (is (true? (.isAlive thread)))
      (Thread/sleep 10)
      (#'http/stop-timer! thread)
      (is (false? (.isAlive thread)))
      (is (zero? @calls))))
  (testing "with handler error"
    (let [calls (atom 0)
          thread (#'http/start-timer!
                  "test-timer"
                  #(do (swap! calls inc)
                       (throw (RuntimeException. "BOOM")))
                  10 0)]
      (is (true? (.isAlive thread)))
      (Thread/sleep 50)
      (is (true? (.isAlive thread)))
      (#'http/stop-timer! thread)
      (is (false? (.isAlive thread)))
      (is (<= 4 @calls 6)))))


(deftest authentication
  (let [client (vault/new-client "https://vault.example.com:8200")]
    (testing "with bad input"
      (is (thrown-with-msg? IllegalArgumentException #"Client authentication must be a map"
            (vault/authenticate! client [])))
      (is (thrown-with-msg? IllegalArgumentException #"containing a client-token"
            (vault/authenticate! client {}))))
    (testing "with token string"
      (is (nil? (vault/authenticate! client "t0p-53cr3t")))
      (is (= {:client-token "t0p-53cr3t"} @(:auth client))))
    (testing "with auth info"
      (is (nil? (vault/authenticate! client {:client-token "t0p-53cr3t"
                                             :ttl 12345})))
      (is (= {:client-token "t0p-53cr3t"
              :ttl 12345}
             @(:auth client))))))


(deftest client-constructor
  (testing "with bad address"
    (is (thrown-with-msg? IllegalArgumentException #"Vault API address must be a string starting with 'http'"
          (http/http-client :foo)))
    (is (thrown-with-msg? IllegalArgumentException #"Vault API address must be a string starting with 'http'"
          (http/http-client "tcp:1234"))))
  (testing "with http addresses"
    (is (= "http://localhost:8200" (:address (vault/new-client "http://localhost:8200"))))
    (is (= "https://vault.example.com:8200" (:address (vault/new-client "https://vault.example.com:8200"))))))
