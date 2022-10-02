(ns vault.auth.github-test
  (:require
    [clojure.test :refer [deftest is testing]]
    [vault.auth.github :as github]
    [vault.client.http :as http]))


(deftest with-mount
  (testing "different mounts"
    (let [client (http/http-client "https://foo.com")]
      (is (nil? (::github/mount client)))
      (is (= "test-mount" (::github/mount (github/with-mount client "test-mount")))))))
