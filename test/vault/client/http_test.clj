(ns vault.client.http-test
  (:require
    [clojure.test :refer [deftest testing is]]
    [org.httpkit.client :as http-client]
    [vault.auth :as auth]
    [vault.client.flow :as f]
    [vault.client.http :as http]
    [vault.client.proto :as proto]))


(defn mock-request
  "Return a function which will simulate an http request callback, with the
  extra response values added to the parameters."
  [response]
  (fn request
    [req callback]
    (callback (assoc response :opts req))))


(deftest call-api
  (let [client {:address "https://vault.test:8200"
                :flow f/sync-handler
                :auth (atom {::auth/token "t0p-53cr5t"})}]
    (testing "with bad arguments"
      (with-redefs [http-client/request (fn [_ _]
                                          (is false "should not be called"))]
        (is (thrown-with-msg? IllegalArgumentException #"call on nil client"
              (http/call-api nil :health :get "sys/health" {})))
        (is (thrown-with-msg? IllegalArgumentException #"call without keyword label"
              (http/call-api client nil :get "sys/health" {})))
        (is (thrown-with-msg? IllegalArgumentException #"call without keyword method"
              (http/call-api client :health nil "sys/health" {})))
        (is (thrown-with-msg? IllegalArgumentException #"call on blank path"
              (http/call-api client :health :get "" {})))))
    (testing "with http call error"
      (with-redefs [http-client/request (mock-request
                                          {:error (RuntimeException. "HTTP BOOM")})]
        (is (thrown-with-msg? RuntimeException #"HTTP BOOM"
              (http/call-api client :foo :get "foo/bar" {})))))
    (testing "with unhandled error"
      (with-redefs [http-client/request (mock-request
                                          {:status 200
                                           :body "{uh oh]"})]
        (is (thrown-with-msg? Exception #"JSON error"
              (http/call-api client :foo :get "foo/bar" {})))))
    (testing "with error response"
      (testing "and default handling"
        (with-redefs [http-client/request (mock-request
                                            {:status 400
                                             :body "{\"errors\": []}"})]
          (is (thrown-with-msg? Exception #"Vault HTTP error on foo/bar \(400\) bad request"
                (http/call-api client :foo :get "foo/bar" {})))))
      (testing "and custom handling"
        (with-redefs [http-client/request (mock-request
                                            {:status 400
                                             :body "{\"errors\": []}"})]
          (is (= :ok (http/call-api
                       client :foo :get "foo/bar"
                       {:handle-error (constantly :ok)}))))))
    (testing "with redirect"
      (testing "and no location header"
        (let [calls (atom 0)]
          (with-redefs [http-client/request (fn [req callback]
                                              (if (= 1 (swap! calls inc))
                                                (callback {:opts req
                                                           :status 303
                                                           :headers {}
                                                           ::http/redirects (::http/redirects req)})
                                                (throw (IllegalStateException.
                                                         "should not reach here"))))]
            (is (thrown-with-msg? Exception #"redirect without Location header"
                  (http/call-api client :foo :get "foo/bar" {}))))))
      (testing "too many times"
        (let [calls (atom 0)]
          (with-redefs [http-client/request (fn [req callback]
                                              (when (= 1 @calls)
                                                (is (= "https://vault.test:8200/foo/baz" (:url req))))
                                              (if (< (swap! calls inc) 5)
                                                (callback {:opts req
                                                           :status 307
                                                           :headers {"Location" "https://vault.test:8200/foo/baz"}
                                                           ::http/redirects (::http/redirects req)})
                                                (throw (IllegalStateException. "should not reach here"))))]
            (is (thrown-with-msg? Exception #"Aborting Vault API request after 3 redirects"
                  (http/call-api client :foo :get "foo/bar" {}))))))
      (testing "successfully"
        (let [calls (atom 0)]
          (with-redefs [http-client/request (fn [req callback]
                                              (when (= 1 @calls)
                                                (is (= "https://vault.test:8200/foo/baz" (:url req))))
                                              (if (< (swap! calls inc) 2)
                                                (callback {:opts req
                                                           :status 307
                                                           :headers {"Location" "https://vault.test:8200/foo/baz"}
                                                           ::http/redirects (::http/redirects req)})
                                                (callback {:opts req
                                                           :status 204
                                                           :headers {}
                                                           ::http/redirects (::http/redirects req)})))]
            (is (nil? (http/call-api client :foo :get "foo/bar" {})))))))
    (testing "with successful response"
      (testing "with default handling"
        (with-redefs [http-client/request (mock-request
                                            {:status 200
                                             :body ""})]
          (is (nil? (http/call-api client :foo :get "foo/bar" {})))))
      (testing "with custom handling"
        (with-redefs [http-client/request (mock-request
                                            {:status 200
                                             :body "{}"})]
          (is (= {:result true}
                 (http/call-api
                   client :foo :get "foo/bar"
                   {:handle-response (constantly {:result true})}))))))))


(deftest authentication
  (let [client (http/http-client "https://vault.test:8200")]
    (testing "with bad input"
      (is (thrown-with-msg? IllegalArgumentException #"Client authentication must be a map"
            (proto/authenticate! client [])))
      (is (thrown-with-msg? IllegalArgumentException #"containing an auth token"
            (proto/authenticate! client {}))))
    (testing "with token string"
      (is (identical? client (proto/authenticate! client "t0p-53cr3t")))
      (is (= {::auth/token "t0p-53cr3t"} (proto/auth-info client))))
    (testing "with auth info"
      (is (identical? client (proto/authenticate!
                               client
                               {:client-token "t0p-53cr3t"
                                :ttl 12345})))
      (is (= {::auth/token "t0p-53cr3t"}
             (proto/auth-info client))))))


(deftest client-constructor
  (testing "with bad address"
    (is (thrown-with-msg? IllegalArgumentException #"Vault API address must be a URL with scheme 'http' or 'https'"
          (http/http-client :foo)))
    (is (thrown-with-msg? IllegalArgumentException #"Vault API address must be a URL with scheme 'http' or 'https'"
          (http/http-client "tcp:1234"))))
  (testing "with http addresses"
    (is (= "http://localhost:8200" (:address (http/http-client "http://localhost:8200"))))
    (is (= "https://vault.test:8200" (:address (http/http-client "https://vault.test:8200"))))))
