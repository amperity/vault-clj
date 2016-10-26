(ns vault.client-test
  (:require
    [clojure.test :refer :all]
    [vault.client :as vault]))


(deftest http-client-instantiation
  (is (thrown? IllegalArgumentException
               (vault/http-client nil)))
  (is (thrown? IllegalArgumentException
               (vault/http-client :foo)))
  (is (instance? vault.client.HTTPClient
                 (vault/http-client "https://vault.example.com"))))


(deftest http-read-checks
  (let [client (vault/http-client "https://vault.example.com")]
    (is (thrown? IllegalArgumentException
                 (vault/read-secret client nil))
        "should throw an exception on non-string path")
    (is (thrown? IllegalStateException
                 (vault/read-secret client "secret/foo/bar"))
        "should throw an exception on unauthenticated client")))
