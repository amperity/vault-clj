(ns vault.sys.auth-test
  (:require
    [clojure.string :as str]
    [clojure.test :refer [is testing deftest]]
    [vault.client.mock :refer [mock-client]]
    [vault.integration :refer [with-dev-server]]
    [vault.sys.auth :as sys.auth]))


;; ## Mock Tests

(deftest mock-read-auth
  (let [client (mock-client)]
    (testing "list-methods"
      (let [result (sys.auth/list-methods client)]
        (is (= ["token/"] (keys result)))
        (is (map? (get result "token/")))
        (is (= "token" (get-in result ["token/" :type])))
        (is (string? (get-in result ["token/" :description])))))
    (testing "read-method-tuning"
      (testing "on token path"
        (let [result (sys.auth/read-method-tuning client "token/")]
          (is (map? result))
          (is (string? (:description result)))
          (is (pos-int? (:default-lease-ttl result)))))
      (testing "on missing path"
        (is (thrown-with-msg? Exception #"cannot fetch sysview"
              (sys.auth/read-method-tuning client "foo/")))))))


;; ## HTTP Tests

(deftest ^:integration api-integration
  (with-dev-server
    (testing "list-methods"
      (let [result (sys.auth/list-methods client)
            auth (get result "token/")]
        (is (= ["token/"] (keys result)))
        (is (map? auth))
        (is (= "token" (:type auth)))
        (is (string? (:uuid auth)))
        (is (string? (:description auth)))))
    (testing "read-method-tuning"
      (testing "on token path"
        (let [result (sys.auth/read-method-tuning client "token/")]
          (is (map? result))
          (is (string? (:description result)))
          (is (pos-int? (:default-lease-ttl result)))))
      (testing "on missing path"
        (is (thrown-with-msg? Exception #"cannot fetch sysview"
              (sys.auth/read-method-tuning client "foo/")))))
    (testing "enable-method!"
      (is (nil? (sys.auth/enable-method! client "github" {:type "github", :description "test github auth"})))
      (let [result (sys.auth/list-methods client)
            auth (get result "github/")]
        (is (contains? result "github/"))
        (is (map? auth))
        (is (= "github" (:type auth)))
        (is (string? (:uuid auth)))
        (is (= "test github auth" (:description auth)))))
    (testing "tune-method!"
      (is (nil? (sys.auth/tune-method! client "github" {:default-lease-ttl 1800, :max-lease-ttl 86400})))
      (let [result (sys.auth/read-method-tuning client "github")]
        (is (map? result))
        (is (= 1800 (:default-lease-ttl result)))
        (is (= 86400 (:max-lease-ttl result)))))
    (testing "disable-method!"
      (is (nil? (sys.auth/disable-method! client "github")))
      (let [result (sys.auth/list-methods client)]
        (is (= ["token/"] (keys result)))))))
