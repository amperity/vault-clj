(ns vault.client.http-test
  (:require
    [clojure.test :refer :all]
    [vault.core :as vault]
    [vault.client.http :refer [http-client] :as h]))

(def example-url "https://vault.example.com")


(deftest http-client-instantiation
  (is (thrown? IllegalArgumentException
               (http-client nil)))
  (is (thrown? IllegalArgumentException
               (http-client :foo)))
  (is (instance? vault.client.http.HTTPClient
                 (http-client example-url))))


(deftest http-read-checks
  (let [client (http-client example-url)]
    (is (thrown? IllegalArgumentException
                 (vault/read-secret client nil))
        "should throw an exception on non-string path")
    (is (thrown? IllegalStateException
                 (vault/read-secret client "secret/foo/bar"))
        "should throw an exception on unauthenticated client")))


(deftest app-role
  (let [api-endpoint (str example-url "/v1/auth/approle/login")
        client (http-client example-url)
        connection-attempt (atom nil)]

    (with-redefs [h/do-api-request
                  (fn [method url req]
                    (reset! connection-attempt url))
                  h/api-auth!
                  (fn [claim auth-ref response] nil)]

      (vault/authenticate! client
                           :app-role
                           {:secret-id "secret"
                            :role-id "role-id"})

      (is (= @connection-attempt api-endpoint)
          (str "should attempt to auth with: " api-endpoint)))))
