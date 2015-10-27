(ns vault.client-test
  (:require
    [clojure.test :refer :all]
    [vault.client :as vault]))


(deftest client-instantiation
  (is (thrown? IllegalArgumentException
               (vault/http-client nil)))
  (is (thrown? IllegalArgumentException
               (vault/http-client :foo)))
  (is (instance? vault_clj.core.HTTPClient
                 (vault/http-client "https://vault.example.com"))))


(deftest secret-reading
  (let [client (vault/http-client "https://vault.example.com")]
    (is (thrown? IllegalArgumentException
                 (vault/read-secret client nil))
        "should throw an exception on non-string path")
    (is (thrown? IllegalStateException
                 (vault/read-secret client "secret/foo/bar"))
        "should throw an exception on unauthenticated client")))
