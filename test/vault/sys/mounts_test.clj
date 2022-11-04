(ns vault.sys.mounts-test
  (:require
    [clojure.test :refer [is testing deftest]]
    [vault.integration :refer [with-dev-server]]
    [vault.sys.mounts :as sys.mounts]))


(deftest ^:integration http-api
  (with-dev-server
    (testing "list-mounts"
      (let [result (sys.mounts/list-mounts client)
            cubbyhole (get result "cubbyhole/")]
        (is (= #{"cubbyhole/" "identity/" "secret/" "sys/"}
               (set (keys result))))
        (is (map? cubbyhole))
        (is (= "cubbyhole" (:type cubbyhole)))
        (is (string? (:uuid cubbyhole)))
        (is (string? (:description cubbyhole)))))
    (testing "enable-secrets!"
      (let [result (sys.mounts/enable-secrets!
                     client
                     "ldap"
                     {:type "openldap"
                      :description "LDAP secret storage"
                      :config {:default-lease-ttl "6m"}})
            ldap (get (sys.mounts/list-mounts client) "ldap/")]
        (is (nil? result))
        (is (map? ldap))
        (is (= "openldap" (:type ldap)))
        (is (= "LDAP secret storage" (:description ldap)))
        (is (= 360 (get-in ldap [:config :default-lease-ttl])))))
    (testing "read-secrets-configuration"
      (let [result (sys.mounts/read-secrets-configuration client "ldap")]
        (is (= "openldap" (:type result)))
        (is (= "LDAP secret storage" (:description result)))
        (is (= 360 (get-in result [:config :default-lease-ttl])))))
    (testing "tune-mount-configuration"
      (is (nil? (sys.mounts/tune-mount-configuration!
                  client
                  "ldap"
                  {:default-lease-ttl 1800
                   :max-lease-ttl 86400
                   :description "new description"})))
      (let [result (sys.mounts/read-mount-configuration client "ldap")]
        (is (= 1800 (:default-lease-ttl result)))
        (is (= 86400 (:max-lease-ttl result)))
        (is (= "new description" (:description result)))))
    (testing "disable-secrets!"
      (is (nil? (sys.mounts/disable-secrets! client "ldap")))
      (let [result (sys.mounts/list-mounts client)]
        (is (= #{"cubbyhole/" "identity/" "secret/" "sys/"}
               (set (keys result))))))))
