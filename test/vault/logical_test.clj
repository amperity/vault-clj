(ns vault.logical-test
  (:require [clojure.test :refer [is testing deftest]]
            [vault.secrets.logical :as vault-logical]
            [vault.client.http])
  (:import (clojure.lang ExceptionInfo)))


(deftest list-secrets-test
  (let [path "path/passed/in"
        token-passed-in "fake-token"
        vault-url "https://vault.example.amperity.com"
        client (vault.core/new-client vault-url)
        response {:auth nil
                  :data {:keys ["foo" "foo/"]}
                  :lease_duration 2764800
                  :lease-id ""
                  :renewable false}]
    (vault.core/authenticate! client :token token-passed-in)
    (testing "List secrets works with valid call"
      (with-redefs
        [clj-http.client/request
         (fn [req]
           (is (= :get (:method req)))
           (is (= (str vault-url "/v1/" path) (:url req)))
           (is (= token-passed-in (get (:headers req) "X-Vault-Token")))
           (is (true? (-> req :query-params :list)))
           {:body response})]
        (is (= ["foo" "foo/"]
               (vault-logical/list-secrets client path)))))))


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
        client (vault.core/new-client vault-url)]
    (vault.core/authenticate! client :token token-passed-in)
    (testing "Read responds correctly if secret is successfully located"
      (with-redefs
        [clj-http.client/request
         (fn [req]
           (is (= :get (:method req)))
           (is (= (str vault-url "/v1/" path-passed-in) (:url req)))
           (is (= token-passed-in (get (:headers req) "X-Vault-Token")))
           {:body lookup-response-valid-path})]
        (is (= {:foo "bar" :ttl "1h"} (vault-logical/read-secret client path-passed-in)))))
    (testing "Read responds correctly if no secret is found"
      (with-redefs
        [clj-http.client/request
         (fn [req]
           (is (= (str vault-url "/v1/" path-passed-in2) (:url req)))
           (is (= token-passed-in (get (:headers req) "X-Vault-Token")))
           (throw (ex-info "not found" {:error [] :status 404})))]
        (try
          (vault-logical/read-secret client path-passed-in2)
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
        client (vault.core/new-client vault-url)]
    (vault.core/authenticate! client :token token-passed-in)
    (testing "Write writes and returns true upon success"
      (with-redefs
        [clj-http.client/request
         (fn [req]
           (is (= :post (:method req)))
           (is (= (str vault-url "/v1/" path-passed-in) (:url req)))
           (is (= token-passed-in (get (:headers req) "X-Vault-Token")))
           (is (= write-data (:form-params req)))
           {:body create-success
            :status 204})]
        (is (true? (vault-logical/write-secret! client path-passed-in write-data)))))
    (testing "Write returns false upon failure"
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
        (is (false? (vault-logical/write-secret! client path-passed-in write-data)))))))

