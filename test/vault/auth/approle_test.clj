(ns vault.auth.approle-test
  (:require
    [clojure.test :refer [deftest is testing]]
    [vault.auth :as auth]
    [vault.auth.approle :as approle]
    [vault.client :as vault]
    [vault.integration :refer [with-dev-server test-client cli]]))


(defn- assert-authenticated-map
  [auth]
  (is (boolean? (:renewable auth)))
  (is (pos-int? (:lease-duration auth)))
  (is (string? (:accessor auth)))
  (is (string? (:client-token auth)))
  (is (map? (:metadata auth)))
  (is (coll? (:token-policies auth))))


(deftest ^:integration http-api
  (with-dev-server
    (testing "with default mount"
      (is (nil? (::approle/mount client)))
      (cli "auth" "enable" "approle")
      (testing "create and read approle"
        (let [role-properties {:bind-secret-id true
                               :secret-id-bound-cidrs ["127.0.0.1/32" "192.0.2.0/23" "192.0.2.0/24"]
                               :secret-id-num-uses 10
                               :secret-id-ttl 75
                               :local-secret-ids false
                               :token-ttl 600
                               :token-max-ttl 1000
                               :token-policies ["foo-policy-1" "foo-policy-2"]
                               :token-bound-cidrs ["127.0.0.1" "192.0.2.0/23" "192.0.2.0/24"]
                               :token-explicit-max-ttl 1000
                               :token-no-default-policy false
                               :token-num-uses 2
                               :token-period 0
                               :token-type "service"}]
          (approle/upsert-role! client "foo" role-properties)
          (is (= role-properties (approle/read-role client "foo")))))
      (testing "list approles"
        (approle/upsert-role! client "baz" {:secret-id-ttl "1m"})
        (is (= #{"foo" "baz"}
               (into #{} (:keys (approle/list-roles client))))))
      (testing "login"
        (let [role-id (:role-id (approle/read-role-id client "foo"))
              secret-id (:secret-id (approle/generate-secret-id! client "foo"))
              original-auth-info (vault/auth-info client)
              response (approle/login client role-id secret-id)
              auth-info (vault/auth-info client)]
          (assert-authenticated-map response)
          (is (= (:client-token response)
                 (::auth/client-token auth-info)))
          (is (not= (::auth/client-token original-auth-info)
                    (::auth/client-token auth-info))))))
    (testing "with alternate mount"
      (cli "auth" "enable" "-path=auth-test" "approle")
      (let [client' (approle/with-mount (test-client) "auth-test")
            role-id (do (approle/upsert-role! client' "bar" {:secret-id-ttl "1m"})
                        (:role-id (approle/read-role-id client' "bar")))
            secret-id (:secret-id (approle/generate-secret-id! client' "bar"))
            original-auth-info (vault/auth-info client')
            response (approle/login client' role-id secret-id)
            auth-info (vault/auth-info client')]
        (is (= "auth-test" (::approle/mount client')))
        (assert-authenticated-map response)
        (is (= (:client-token response)
               (::auth/client-token auth-info)))
        (is (not= (::auth/client-token original-auth-info)
                  (::auth/client-token auth-info)))))))
