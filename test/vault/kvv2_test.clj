(ns vault.kvv2-test
  (:require
    [clojure.test :refer [testing deftest is]])
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
                    :delete_version_after}]
    (vault.core/authenticate! client :token {:client-token token-passed-in})
    (testing "Config can be updated with valid call"
      (with-redefs
        [clj-http.client/post
         (fn [req]
           (is (= (str vault-url "/v1/" mount "/config") (:url req)))
           (is (= token-passed-in (get (:headers req) "X-Vault-Token")))
           (is (= new-config (:data req))))]
        (is (true? (vault-kv/write-config! client mount new-config)))))))


(deftest read-config-test
  (let [config {:max_versions 5
                :cas_require false
                :delete_version_after}
        mount "mount"
        token-passed-in "fake-token"
        vault-url "https://vault.example.amperity.com"
        client (vault.core/new-client vault-url)]
    (vault.core/authenticate! client :token token-passed-in)
    (testing "Config can be read with valid call"
      (with-redefs
        [clj-http.client/get
         (fn [url opts]
           (is (= (str vault-url "/v1/" mount "/config") url))
           (is (= token-passed-in (get (:headers opts) "X-Vault-Token")))
           {:data config})]
        (is (= config (vault-kv/read-config client mount)))))))


(deftest read-test
  (let [lookup-response-valid-path {:data {:data     {:foo "bar"}
                                           :metadata {:created_time  "2018-03-22T02:24:06.945319214Z"
                                                      :deletion_time ""
                                                      :destroyed     false
                                                      :version       1}}}
        path-passed-in "path/passed/in"
        token-passed-in "fake-token"
        vault-url "https://vault.example.amperity.com"
        client (vault.core/new-client vault-url)]
    (vault.core/authenticate! client :token token-passed-in)
    (testing "Read responds correctly if secret is successfully located"
      (with-redefs
        [clj-http.client/get
         (fn [url opts]
           (is (= (str vault-url "/v1/data/" path-passed-in) url))
           (is (= token-passed-in (get (:headers opts) "X-Vault-Token")))
           lookup-response-valid-path)]
        (is (= {:foo "bar"} (vault-kv/read client vault-url)))))
    (testing "Read responds correctly if no secret is found"
      (with-redefs
        [clj-http.client/get
         (fn [url opts]
           (is (= (str vault-url "/v1/data/" path-passed-in) url))
           (is (= token-passed-in (get (:headers opts) "X-Vault-Token")))
           {:errors []})]
        (try
          (vault-kv/read client vault-url)
          (is false)
          (catch ExceptionInfo e
            (is (= {:status 404} (ex-data e)))))))))


(deftest write!-test
  (let [create-success {:data {:created_time  "2018-03-22T02:24:06.945319214Z"
                               :deletion_time ""
                               :destroyed     false
                               :version       1}}
        write-data {:foo "bar"
                    :zip "zap"}
        options {:cas 0}
        path-passed-in "path/passed/in"
        token-passed-in "fake-token"
        vault-url "https://vault.example.amperity.com"
        client (vault.core/new-client vault-url)]
    (vault.core/authenticate! client :token token-passed-in)
    (testing "Write writes and returns true upon success"
      (with-redefs
        [clj-http.client/post
         (fn [url req]
           (is (= (str vault-url "/v1/data/" path-passed-in) url))
           (is (= token-passed-in (get (:headers req) "X-Vault-Token")))
           (is (= {:data write-data
                   :options options}
                  (:data req)))
           create-success)]
        (is (true? (vault-kv/write! client vault-url write-data options)))))
    (testing "Write returns false upon failure"
      (with-redefs
        [clj-http.client/post
         (fn [url req]
           (is (= (str vault-url "/v1/data/" path-passed-in) url))
           (is (= token-passed-in (get (:headers req) "X-Vault-Token")))
           (is (= {:data write-data
                   :options options}
                  (:data req)))
           {:errors []
            :status 404})]
        (is (false? (vault-kv/write! client vault-url write-data options)))))))
