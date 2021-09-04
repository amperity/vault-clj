(ns vault.client.http-test
  (:require
    [clojure.test :refer [deftest testing is]]
    [vault.client :as vault]
    [vault.client.http :as http]
    [vault.client.response :as resp]))


(deftest call-api
  (let [client {:address "https://vault.test:8200"
                :response-handler resp/sync-handler
                :auth (atom {:client-token "t0p-53cr5t"})}]
    (testing "with bad arguments"
      (with-redefs [org.httpkit.client/request (fn [req callback]
                                                 (is false "should not be called"))]
        (is (thrown-with-msg? IllegalArgumentException #"call on nil client"
              (http/call-api nil :get "sys/health" {})))
        (is (thrown-with-msg? IllegalArgumentException #"call without keyword method"
              (http/call-api client nil "sys/health" {})))
        (is (thrown-with-msg? IllegalArgumentException #"call on blank path"
              (http/call-api client :get "" {})))))
    (testing "with http call error"
      (with-redefs [org.httpkit.client/request (fn [req callback]
                                                 (callback {:error (RuntimeException. "HTTP BOOM")}))]
        (is (thrown-with-msg? RuntimeException #"HTTP BOOM"
              (http/call-api client :get "foo/bar" {})))))
    (testing "with unhandled error"
      (with-redefs [org.httpkit.client/request (fn [req callback]
                                                 (callback {:status 200
                                                            :body "{uh oh]"}))]
        (is (thrown-with-msg? Exception #"JSON error"
              (http/call-api client :get "foo/bar" {})))))
    (testing "with error response"
      (testing "and default handling"
        (with-redefs [org.httpkit.client/request (fn [req callback]
                                                   (callback {:status 400
                                                              :body "{\"errors\": []}"}))]
          (is (thrown-with-msg? Exception #"Vault HTTP error: bad request"
                (http/call-api client :get "foo/bar" {})))))
      (testing "and custom handling"
        (with-redefs [org.httpkit.client/request (fn [req callback]
                                                   (callback {:status 400
                                                              :body "{\"errors\": []}"}))]
          (is (= :ok (http/call-api
                       client :get "foo/bar"
                       {:handle-error (constantly :ok)}))))))
    (testing "with redirect"
      (testing "and no location header"
        (let [calls (atom 0)]
          (with-redefs [org.httpkit.client/request (fn [req callback]
                                                     (if (= 1 (swap! calls inc))
                                                       (callback {:status 303
                                                                  :headers {}
                                                                  ::http/redirects (::http/redirects req)})
                                                       (throw (IllegalStateException.
                                                                "should not reach here"))))]
            (is (thrown-with-msg? Exception #"redirect without Location header"
                  (http/call-api client :get "foo/bar" {}))))))
      (testing "too many times"
        (let [calls (atom 0)]
          (with-redefs [org.httpkit.client/request (fn [req callback]
                                                     (when (= 1 @calls)
                                                       (is (= "https://vault.test:8200/foo/baz" (:url req))))
                                                     (if (< (swap! calls inc) 5)
                                                       (callback {:status 307
                                                                  :headers {"Location" "https://vault.test:8200/foo/baz"}
                                                                  ::http/redirects (::http/redirects req)})
                                                       (throw (IllegalStateException. "should not reach here"))))]
            (is (thrown-with-msg? Exception #"Aborting Vault API request after 3 redirects"
                  (http/call-api client :get "foo/bar" {}))))))
      (testing "successfully"
        (let [calls (atom 0)]
          (with-redefs [org.httpkit.client/request (fn [req callback]
                                                     (when (= 1 @calls)
                                                       (is (= "https://vault.test:8200/foo/baz" (:url req))))
                                                     (if (< (swap! calls inc) 2)
                                                       (callback {:status 307
                                                                  :headers {"Location" "https://vault.test:8200/foo/baz"}
                                                                  ::http/redirects (::http/redirects req)})
                                                       (callback {:status 204
                                                                  :headers {}
                                                                  ::http/redirects (::http/redirects req)})))]
            (is (nil? (http/call-api client :get "foo/bar" {})))))))
    (testing "with successful response"
      (testing "with default handling"
        (with-redefs [org.httpkit.client/request (fn [req callback]
                                                   (callback {:status 200
                                                              :body ""}))]
          (is (nil? (http/call-api client :get "foo/bar" {})))))
      (testing "with custom handling"
        (with-redefs [org.httpkit.client/request (fn [req callback]
                                                   (callback {:status 200
                                                              :body "{}"}))]
          (is (= {:result true}
                 (http/call-api
                   client :get "foo/bar"
                   {:handle-response (constantly {:result true})}))))))))


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
  (let [client (vault/new-client "https://vault.test:8200")]
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
    (is (= "https://vault.test:8200" (:address (vault/new-client "https://vault.test:8200"))))))
