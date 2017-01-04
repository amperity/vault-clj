(ns vault.client.http-test
  (:require
    [clojure.test :refer :all]
    [vault.core :as vault]
    [vault.client.http :refer [http-client]]))


(deftest http-client-instantiation
  (is (thrown? IllegalArgumentException
               (http-client nil)))
  (is (thrown? IllegalArgumentException
               (http-client :foo)))
  (is (instance? vault.client.http.HTTPClient
                 (http-client "https://vault.example.com"))))


(deftest http-read-checks
  (let [client (http-client "https://vault.example.com")]
    (is (thrown? IllegalArgumentException
                 (vault/read-secret client nil))
        "should throw an exception on non-string path")
    #_
    (is (thrown? IllegalStateException
                 (vault/read-secret client "secret/foo/bar"))
        "should throw an exception on unauthenticated client")))
