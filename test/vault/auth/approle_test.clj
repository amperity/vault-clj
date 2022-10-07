(ns vault.auth.approle-test
  (:require
    [clojure.data.json :as json]
    [clojure.test :refer [deftest is testing]]
    [vault.auth :as auth]
    [vault.auth.approle :as approle]
    [vault.client :as vault]
    [vault.integration :refer [with-dev-server cli]]))


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
    (testing "login"
      (testing "with default mount"
        (cli "auth" "enable" "approle")
        (approle/upsert-role client "foo" {:secret-id-ttl "1m"})
        (let [role-id (:role-id (approle/read-role-id client "foo"))
              secret-id (:secret-id (approle/generate-secret-id client "foo"))
              original-auth-info (vault/auth-info client)
              response (approle/login client role-id secret-id)
              auth-info (vault/auth-info client)]
          (is (nil? (::approle/mount client)))
          (assert-authenticated-map response)
          (is (= (:client-token response)
                 (::auth/client-token auth-info)))
          (is (not= (::auth/client-token original-auth-info)
                    (::auth/client-token auth-info)))))
      (testing "with alternate mount"
        (cli "auth" "enable" "-path=auth-test" "approle")
        (let [client' (approle/with-mount client "auth-test")
              role-id (do (approle/upsert-role client' "bar" {:secret-id-ttl "1m"})
                          (:role-id (approle/read-role-id client' "bar")))
              secret-id (:secret-id (approle/generate-secret-id client' "bar"))
              original-auth-info (vault/auth-info client')
              response (approle/login client' role-id secret-id)
              auth-info (vault/auth-info client')]
          (is (= "auth-test" (::approle/mount client')))
          (assert-authenticated-map response)
          (is (= (:client-token response)
                 (::auth/client-token auth-info)))
          (is (not= (::auth/client-token original-auth-info)
                    (::auth/client-token auth-info))))))))
