(ns vault.auth.kubernetes-test
  (:require
    [clojure.test :refer [deftest is testing]]
    [vault.auth.kubernetes :as k8s]
    [vault.client.http :as http]))


(deftest with-mount
  (testing "different mounts"
    (let [client (http/http-client "https://foo.com")]
      (is (nil? (::k8s/mount client)))
      (is (= "test-mount" (::k8s/mount (k8s/with-mount client "test-mount")))))))
