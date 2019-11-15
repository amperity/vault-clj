(ns vault.secrets.kvv2-test
  (:require
    [clojure.test :refer [testing deftest is]]
    [vault.client.http :as http-client]
    [vault.secrets.kvv2 :as vault-kv])
  (:import
    (clojure.lang
      ExceptionInfo)))


(deftest write-config!-test
  (let [mount "mount"
        token-passed-in "fake-token"
        vault-url "https://vault.example.amperity.com"
        client (vault.core/new-client vault-url)
        new-config {:max_versions 5
                    :cas_require false
                    :delete_version_after "3h25m19s"}]
    (vault.core/authenticate! client :token token-passed-in)
    (testing "Config can be updated with valid call"
      (with-redefs
        [clj-http.client/request
         (fn [req]
           (is (= :post (:method req)))
           (is (= (str vault-url "/v1/" mount "/config") (:url req)))
           (is (= token-passed-in (get (:headers req) "X-Vault-Token")))
           (is (= new-config (:form-params req)))
           {:status 204})]
        (is (true? (vault-kv/write-config! client mount new-config)))))))


(deftest read-config-test
  (let [config {:max_versions 5
                :cas_require false
                :delete_version_after "3h25m19s"}
        mount "mount"
        token-passed-in "fake-token"
        vault-url "https://vault.example.amperity.com"
        client (vault.core/new-client vault-url)]
    (vault.core/authenticate! client :token token-passed-in)
    (testing "Config can be read with valid call"
      (with-redefs
        [clj-http.client/request
         (fn [req]
           (is (= :get (:method req)))
           (is (= (str vault-url "/v1/" mount "/config") (:url req)))
           (is (= token-passed-in (get (:headers req) "X-Vault-Token")))
           {:body {:data config}})]
        (is (= config (vault-kv/read-config client mount)))))))


(deftest read-test
  (let [lookup-response-valid-path {:data {:data     {:foo "bar"}
                                           :metadata {:created_time  "2018-03-22T02:24:06.945319214Z"
                                                      :deletion_time ""
                                                      :destroyed     false
                                                      :version       1}}}
        mount "mount"
        path-passed-in "path/passed/in"
        token-passed-in "fake-token"
        vault-url "https://vault.example.amperity.com"
        client (vault.core/new-client vault-url)]
    (vault.core/authenticate! client :token token-passed-in)
    (testing "Read responds correctly if secret is successfully located"
      (with-redefs
        [clj-http.client/request
         (fn [req]
           (is (= :get (:method req)))
           (is (= (str vault-url "/v1/" mount "/data/" path-passed-in) (:url req)))
           (is (= token-passed-in (get (:headers req) "X-Vault-Token")))
           {:body lookup-response-valid-path})]
        (is (= {:foo "bar"} (vault-kv/read-secret client mount path-passed-in)))))
    (testing "Read responds correctly if no secret is found"
      (with-redefs
        [clj-http.client/request
         (fn [req]
           (is (= :get (:method req)))
           (is (= (str vault-url "/v1/" mount "/data/different/path") (:url req)))
           (is (= token-passed-in (get (:headers req) "X-Vault-Token")))
           (throw (ex-info "not found" {:errors [] :status 404 :type ::api-error})))]
        (try
          (is (= {:default-val :is-here}
                 (vault-kv/read-secret
                   client
                   mount
                   "different/path"
                   {:not-found {:default-val :is-here}})))
          (vault-kv/read-secret client mount "different/path")
          (is false)
          (catch ExceptionInfo e
            (is (= {:errors nil
                    :status 404
                    :type   ::http-client/api-error}
                   (ex-data e)))))))))


(deftest write!-test
  (let [create-success {:data {:created_time  "2018-03-22T02:24:06.945319214Z"
                               :deletion_time ""
                               :destroyed     false
                               :version       1}}
        write-data {:foo "bar"
                    :zip "zap"}
        mount "mount"
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
           (is (= (str vault-url "/v1/" mount "/data/" path-passed-in) (:url req)))
           (is (= token-passed-in (get (:headers req) "X-Vault-Token")))
           (is (= {:data write-data}
                  (:form-params req)))
           {:body create-success
            :status 200})]
        (is (= (:data create-success) (vault-kv/write-secret! client mount path-passed-in write-data)))))
    (testing "Write returns false upon failure"
      (with-redefs
        [clj-http.client/request
         (fn [req]
           (is (= :post (:method req)))
           (is (= (str vault-url "/v1/" mount "/data/other-path") (:url req)))
           (is (= token-passed-in (get (:headers req) "X-Vault-Token")))
           (is (= {:data write-data}
                  (:form-params req)))
           {:errors []
            :status 404})]
        (is (false? (vault-kv/write-secret! client mount "other-path" write-data)))))))
