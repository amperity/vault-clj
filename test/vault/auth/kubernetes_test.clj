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


(deftest login
  (testing "should throw an exception if jwt or role is missing from the params"
    (let [client (http/http-client "https://foo.com")]
      (is (thrown-with-msg?
            IllegalArgumentException
            #"Kubernetes auth params must include :jwt"
            (k8s/login client {})))
      (is (thrown-with-msg?
            IllegalArgumentException
            #"Kubernetes auth params must include :jwt"
            (k8s/login client {:role "dev"})))
      (is (thrown-with-msg?
            IllegalArgumentException
            #"Kubernetes auth params must include :role"
            (k8s/login client {:jwt "eyJhfoo"}))))))
