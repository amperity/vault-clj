(ns vault.auth.userpass-test
  (:require
    [clojure.test :refer [deftest is testing]]
    [vault.auth.userpass :as userpass]
    [vault.integration :refer [with-dev-server cli]]))


(deftest ^:integration http-api
  (with-dev-server
    (cli "auth" "enable" "userpass")
    (cli "write" "auth/userpass/users/foo" "password=bar")
    (testing "login"
      (let [auth (userpass/login client "foo" "bar")]
        (is (string? (:accessor auth)))
        (is (string? (:client-token auth)))
        (is (pos-int? (:lease-duration auth)))))))
