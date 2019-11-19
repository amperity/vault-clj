(ns vault.client.mock-test
  (:require
    [clojure.test :refer :all]
    [vault.core :as vault]))


(defn mock-client-authenticated
  "A mock vault client using the secrets found in `resources/secret-fixture.edn`"
  []
  (let [client (vault/new-client "mock:amperity/gocd/secret/vault/secret-fixture.edn")]
    (vault/authenticate! client :token "fake-token")
    client))


(deftest create-token!-test
  (testing "The return value of create-token is correct when not wrapped"
    (let [result (vault/create-token! (mock-client-authenticated) {:no-default-policy true})]
      (is (= ["root"] (:policies result)))
      (is (= false (:renewable result)))
      (is (= "" (:entity-id result)))
      (is (= ["root"] (:token-policies result)))
      (is (and (string? (:accessor result)) (not (empty? (:accessor result)))))
      (is (= 0 (:lease-duration result)))
      (is (= "service" (:token-type result)))
      (is (= false (:orphan result)))
      (is (and (string? (:client-token result)) (not (empty? (:client-token result)))))
      (is (contains? result :metadata))))
  (testing "The return value of create-token is correct when not wrapped and some options are specified"
    (let [result (vault/create-token! (mock-client-authenticated) {:policies ["hello" "goodbye"]
                                                                   :ttl "7d"})]
      (is (= ["default" "hello" "goodbye"] (:policies result)))
      (is (= false (:renewable result)))
      (is (= "" (:entity-id result)))
      (is (= ["default" "hello" "goodbye"] (:token-policies result)))
      (is (and (string? (:accessor result)) (not (empty? (:accessor result)))))
      (is (= 604800 (:lease-duration result)))
      (is (= "service" (:token-type result)))
      (is (= false (:orphan result)))
      (is (and (string? (:client-token result)) (not (empty? (:client-token result)))))
      (is (contains? result :metadata))))
  (testing "The return value of create-token is correct when not wrapped and some less common options are specified"
    (let [result (vault/create-token! (mock-client-authenticated) {:policies ["hello" "goodbye"]
                                                                   :ttl "10s"
                                                                   :no-parent true
                                                                   :no-default-policy true
                                                                   :renewable true})]
      (is (= ["hello" "goodbye"] (:policies result)))
      (is (= true (:renewable result)))
      (is (= "" (:entity-id result)))
      (is (= ["hello" "goodbye"] (:token-policies result)))
      (is (and (string? (:accessor result)) (not (empty? (:accessor result)))))
      (is (= 10 (:lease-duration result)))
      (is (= "service" (:token-type result)))
      (is (= true (:orphan result)))
      (is (and (string? (:client-token result)) (not (empty? (:client-token result)))))
      (is (contains? result :metadata))))
  (testing "The return value of create-token is correct when wrapped"
    (let [result (vault/create-token! (mock-client-authenticated) {:wrap-ttl "2h"})]
      (is (and (string? (:token result)) (not (empty? (:token result)))))
      (is (and (string? (:accessor result)) (not (empty? (:accessor result)))))
      (is (= 7200 (:ttl result)))
      (is (and (string? (:creation-time result)) (not (empty? (:creation-time result)))))
      (is (= "auth/token/create" (:creation-path result)))
      (is (and (string? (:wrapped-accessor result)) (not (empty? (:wrapped-accessor result))))))))
