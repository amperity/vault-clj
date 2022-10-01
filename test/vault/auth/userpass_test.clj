(ns vault.auth.userpass-test
  (:require
    [clojure.test :refer [deftest is testing]]
    [vault.auth.userpass :as userpass]
    [vault.integration :refer [with-dev-server cli]]))


(deftest ^:integration http-api
  (with-dev-server
    (testing "login"
      (testing "with default mount"
        (cli "auth" "enable" "userpass")
        (cli "write" "auth/userpass/users/foo" "password=bar")
        (let [auth (userpass/login client "foo" "bar")]
          (is (string? (:accessor auth)))
          (is (string? (:client-token auth)))
          (is (pos-int? (:lease-duration auth)))))
      (testing "with alternate mount"
        (cli "auth" "enable" "-path=auth-test" "userpass")
        (cli "write" "auth/auth-test/users/baz" "password=qux")
        (let [client' (userpass/with-mount client "auth-test")
              auth (userpass/login client' "baz" "qux")]
          (is (string? (:accessor auth)))
          (is (string? (:client-token auth)))
          (is (pos-int? (:lease-duration auth))))))))
