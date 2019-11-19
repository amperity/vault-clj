(ns vault.secrets.kvv1-test
  (:require
    [clojure.test :refer [is testing deftest]]
    [vault.client.http]
    [vault.client.http :as http-client]
    [vault.core :as vault]
    [vault.secrets.kvv1 :as vault-kvv1])
  (:import
    (clojure.lang
      ExceptionInfo)))


(deftest list-secrets-test
  (let [path "path/passed/in"
        token-passed-in "fake-token"
        vault-url "https://vault.example.amperity.com"
        client (http-client/http-client vault-url)
        response {:auth nil
                  :data {:keys ["foo" "foo/"]}
                  :lease_duration 2764800
                  :lease-id ""
                  :renewable false}]
    (vault/authenticate! client :token token-passed-in)
    (testing "List secrets has correct response and sends correct request"
      (with-redefs
        [clj-http.client/request
         (fn [req]
           (is (= :get (:method req)))
           (is (= (str vault-url "/v1/" path) (:url req)))
           (is (= token-passed-in (get (:headers req) "X-Vault-Token")))
           (is (true? (-> req :query-params :list)))
           {:body response})]
        (is (= ["foo" "foo/"]
               (vault-kvv1/list-secrets client path)))))))


(deftest read-secret-test
  (let [lookup-response-valid-path {:auth           nil
                                    :data           {:foo "bar"
                                                     :ttl "1h"}
                                    :lease_duration 3600
                                    :lease_id       ""
                                    :renewable      false}
        path-passed-in "path/passed/in"
        path-passed-in2 "path/passed/in2"
        token-passed-in "fake-token"
        vault-url "https://vault.example.amperity.com"
        client (http-client/http-client vault-url)]
    (vault/authenticate! client :token token-passed-in)
    (testing "Read secrets sends correct request and responds correctly if secret is successfully located"
      (with-redefs
        [clj-http.client/request
         (fn [req]
           (is (= :get (:method req)))
           (is (= (str vault-url "/v1/" path-passed-in) (:url req)))
           (is (= token-passed-in (get (:headers req) "X-Vault-Token")))
           {:body lookup-response-valid-path})]
        (is (= {:foo "bar" :ttl "1h"} (vault-kvv1/read-secret client path-passed-in)))))
    (testing "Read secrets sends correct request and responds correctly if no secret is found"
      (with-redefs
        [clj-http.client/request
         (fn [req]
           (is (= (str vault-url "/v1/" path-passed-in2) (:url req)))
           (is (= token-passed-in (get (:headers req) "X-Vault-Token")))
           (throw (ex-info "not found" {:error [] :status 404})))]
        (try
          (vault-kvv1/read-secret client path-passed-in2)
          (catch ExceptionInfo e
            (is (= {:errors nil
                    :status 404
                    :type   :vault.client.http/api-error}
                   (ex-data e)))))))))


(deftest write-test
  (let [create-success {:data {:created_time  "2018-03-22T02:24:06.945319214Z"
                               :deletion_time ""
                               :destroyed     false
                               :version       1}}
        write-data {:foo "bar"
                    :zip "zap"}
        path-passed-in "path/passed/in"
        token-passed-in "fake-token"
        vault-url "https://vault.example.amperity.com"
        client (http-client/http-client vault-url)]
    (vault/authenticate! client :token token-passed-in)
    (testing "Write secrets sends correct request and returns true upon success"
      (with-redefs
        [clj-http.client/request
         (fn [req]
           (is (= :post (:method req)))
           (is (= (str vault-url "/v1/" path-passed-in) (:url req)))
           (is (= token-passed-in (get (:headers req) "X-Vault-Token")))
           (is (= write-data (:form-params req)))
           {:body create-success
            :status 204})]
        (is (true? (vault-kvv1/write-secret! client path-passed-in write-data)))))
    (testing "Write secrets sends correct request and returns false upon failure"
      (with-redefs
        [clj-http.client/request
         (fn [req]
           (is (= :post (:method req)))
           (is (= (str vault-url "/v1/" path-passed-in) (:url req)))
           (is (= token-passed-in (get (:headers req) "X-Vault-Token")))
           (is (= write-data
                  (:form-params req)))
           {:errors []
            :status 400})]
        (is (false? (vault-kvv1/write-secret! client path-passed-in write-data)))))))

