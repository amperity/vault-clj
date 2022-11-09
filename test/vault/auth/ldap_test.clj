(ns vault.auth.ldap-test
  (:require
    [clojure.test :refer [deftest is testing]]
    [vault.auth.ldap :as ldap]
    [vault.client.http :as http]))


(deftest with-mount
  (testing "different mounts"
    (let [client (http/http-client "https://foo.com")]
      (is (nil? (::ldap/mount client)))
      (is (= "test-mount" (::ldap/mount (ldap/with-mount client "test-mount")))))))
