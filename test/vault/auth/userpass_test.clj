(ns vault.auth.userpass-test
  (:require
    [clojure.test :refer [deftest is testing]]
    [vault.auth :as auth]
    [vault.auth.userpass :as userpass]
    [vault.client :as vault]
    [vault.integration :refer [with-dev-server cli]]))


(defn- assert-authenticated-map
  [auth]
  (is (string? (:accessor auth)))
  (is (string? (:client-token auth)))
  (is (pos-int? (:lease-duration auth))))


(deftest ^:integration http-api
  (with-dev-server
    (testing "login"
      (testing "with default mount"
        (cli "auth" "enable" "userpass")
        (cli "write" "auth/userpass/users/foo" "password=bar")
        (let [original-auth-info (vault/auth-info client)
              response (userpass/login client "foo" "bar")
              auth-info (vault/auth-info client)]
          (assert-authenticated-map response)
          (is (= (:client-token response)
                 (::auth/client-token auth-info)))
          (is (not= (::auth/client-token original-auth-info)
                    (::auth/client-token auth-info)))))
      (testing "with alternate mount"
        (cli "auth" "enable" "-path=auth-test" "userpass")
        (cli "write" "auth/auth-test/users/baz" "password=qux")
        (let [original-auth-info (vault/auth-info client)
              client' (userpass/with-mount client "auth-test")
              response (userpass/login client' "baz" "qux")
              auth-info (vault/auth-info client)]
          (assert-authenticated-map response)
          (is (= (:client-token response)
                 (::auth/client-token (vault/auth-info client))))
          (is (not= (::auth/client-token original-auth-info)
                    (::auth/client-token auth-info))))))))
