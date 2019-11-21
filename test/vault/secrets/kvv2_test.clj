(ns vault.secrets.kvv2-test
  (:require
    [clojure.test :refer [testing deftest is]]
    [vault.api-util :as api-util]
    [vault.client.http :as http-client]
    [vault.client.mock-test :as mock-test]
    [vault.core :as vault]
    [vault.secrets.kvv2 :as vault-kvv2])
  (:import
    (clojure.lang
      ExceptionInfo)))


(deftest write-config!-test
  (let [mount "mount"
        token-passed-in "fake-token"
        vault-url "https://vault.example.amperity.com"
        client (http-client/http-client vault-url)
        new-config {:max_versions 5
                    :cas_require false
                    :delete_version_after "3h25m19s"}]
    (vault/authenticate! client :token token-passed-in)
    (testing "Write config sends correct request and returns true on valid call"
      (with-redefs
        [clj-http.client/request
         (fn [req]
           (is (= :post (:method req)))
           (is (= (str vault-url "/v1/" mount "/config") (:url req)))
           (is (= token-passed-in (get (:headers req) "X-Vault-Token")))
           (is (= new-config (:form-params req)))
           {:status 204})]
        (is (true? (vault-kvv2/write-config! client mount new-config)))))))


(deftest read-config-test
  (let [config {:max_versions 5
                :cas_require false
                :delete_version_after "3h25m19s"}
        mount "mount"
        token-passed-in "fake-token"
        vault-url "https://vault.example.amperity.com"
        client (http-client/http-client vault-url)]
    (vault/authenticate! client :token token-passed-in)
    (testing "Read config sends correct request and returns the config with valid call"
      (with-redefs
        [clj-http.client/request
         (fn [req]
           (is (= :get (:method req)))
           (is (= (str vault-url "/v1/" mount "/config") (:url req)))
           (is (= token-passed-in (get (:headers req) "X-Vault-Token")))
           {:body {:data config}})]
        (is (= config (vault-kvv2/read-config client mount)))))))


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
        client (http-client/http-client vault-url)]
    (vault/authenticate! client :token token-passed-in)
    (testing "Read secrets sends correct request and responds correctly if secret is successfully located"
      (with-redefs
        [clj-http.client/request
         (fn [req]
           (is (= :get (:method req)))
           (is (= (str vault-url "/v1/" mount "/data/" path-passed-in) (:url req)))
           (is (= token-passed-in (get (:headers req) "X-Vault-Token")))
           {:body lookup-response-valid-path})]
        (is (= {:foo "bar"} (vault-kvv2/read-secret client mount path-passed-in)))))
    (testing "Read secrets sends correct request and responds correctly if no secret is found"
      (with-redefs
        [clj-http.client/request
         (fn [req]
           (is (= :get (:method req)))
           (is (= (str vault-url "/v1/" mount "/data/different/path") (:url req)))
           (is (= token-passed-in (get (:headers req) "X-Vault-Token")))
           (throw (ex-info "not found" {:errors [] :status 404 :type :vault.api-util/api-error})))]
        (try
          (is (= {:default-val :is-here}
                 (vault-kvv2/read-secret
                   client
                   mount
                   "different/path"
                   {:not-found {:default-val :is-here}})))

          (vault-kvv2/read-secret client mount "different/path")
          (is false)
          (catch ExceptionInfo e
            (is (= {:errors nil
                    :status 404
                    :type   ::api-util/api-error}
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
        client (http-client/http-client vault-url)]
    (vault/authenticate! client :token token-passed-in)
    (testing "Write secrets sends correct request and returns true upon success"
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
        (is (= (:data create-success) (vault-kvv2/write-secret! client mount path-passed-in write-data)))))
    (testing "Write secrets sends correct request and returns false upon failure"
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
        (is (false? (vault-kvv2/write-secret! client mount "other-path" write-data)))))))


;; -------- Mock Client -------------------------------------------------------

(defn mock-client-kvv2
  "Creates a mock client with the data in `vault/secrets/secret-fixture-kvv2.edn`"
  []
  (mock-test/mock-client-authenticated "vault/secrets/secret-fixture-kvv2.edn"))


(deftest mock-client-test
  (testing "Mock client can correctly read values it was initialized with"
    (is (= {:batman         "Bruce Wayne"
            :captain-marvel "Carol Danvers"}
           (vault-kvv2/read-secret (mock-client-kvv2) "mount" "identities"))))
  (testing "Mock client correctly responds with a 404 to reading non-existent paths"
    (is (thrown-with-msg? ExceptionInfo #"No such secret: mount/data/hello"
          (vault-kvv2/read-secret (mock-client-kvv2) "mount" "hello")))
    (is (thrown-with-msg? ExceptionInfo #"No such secret: mount/data/identities"
          (vault-kvv2/read-secret (vault/new-client "mock:-") "mount" "identities"))))
  (testing "Mock client can write/update and read data"
    (let [client (mock-client-kvv2)]
      (is (thrown-with-msg? ExceptionInfo #"No such secret: mount/data/hello"
            (vault-kvv2/read-secret client "mount" "hello")))
      (is (true? (vault-kvv2/write-secret! client "mount" "hello" {:and-i-say "goodbye"})))
      (is (true? (vault-kvv2/write-secret! client "mount" "identities" {:intersect "Chuck"})))
      (is (= {:and-i-say "goodbye"}
             (vault-kvv2/read-secret client "mount" "hello")))
      (is (= {:intersect       "Chuck"}
             (vault-kvv2/read-secret client "mount" "identities"))))))
