(ns vault.auth.ldap-test
  (:require
    [clojure.test :refer [deftest is testing]]
    [vault.auth :as auth]
    [vault.auth.ldap :as ldap]
    [vault.client :as vault]
    [vault.client.http :as http]
    [vault.integration :refer [with-dev-server cli]]))


(deftest with-mount
  (testing "different mounts"
    (let [client (http/http-client "https://foo.com")]
      (is (nil? (::ldap/mount client)))
      (is (= "test-mount" (::ldap/mount (ldap/with-mount client "test-mount")))))))


(deftest ^:integration http-api
  (let [ldap-url (or (System/getenv "VAULT_LDAP_URL") "localhost:389")
        ldap-domain (or (System/getenv "VAULT_LDAP_DOMAIN") "dc=test,dc=com")
        admin-pass (System/getenv "VAULT_LDAP_ADMIN_PASS")
        login-user (System/getenv "VAULT_LDAP_LOGIN_USER")
        login-pass (System/getenv "VAULT_LDAP_LOGIN_PASS")]
    (when (and admin-pass login-user login-pass)
      (with-dev-server
        (cli "auth" "enable" "ldap")
        (cli "write" "auth/ldap/config"
             (str "url=ldap://" ldap-url)
             (str "userdn=ou=users," ldap-domain)
             (str "groupdn=ou=groups," ldap-domain)
             (str "binddn=cn=admin," ldap-domain)
             (str "bindpass=" admin-pass))
        (testing "login"
          (reset! (:auth client) {})
          (let [response (ldap/login client login-user login-pass)
                auth-info (vault/auth-info client)]
            (is (string? (:client-token response)))
            (is (string? (:accessor response)))
            (is (pos-int? (:lease-duration response)))
            (is (true? (:orphan response)))
            (is (zero? (:num-uses response)))
            (is (= ["default"] (:token-policies response)))
            (is (= {:username login-user} (:metadata response)))
            (is (= (:client-token response)
                   (::auth/token auth-info)))))))))
