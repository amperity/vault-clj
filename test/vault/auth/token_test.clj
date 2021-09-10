(ns vault.auth.token-test
  (:require
    [clojure.test :refer [is testing deftest]]
    [vault.auth.token :as token]
    [vault.client.mock :refer [mock-client]]
    [vault.integration :refer [with-dev-server cli test-client]]))


(deftest mock-api
  (let [client (mock-client)]
    (testing "lookup-token"
      (is (thrown-with-msg? Exception #"bad token"
            (token/lookup-token client {:token "foo"})))
      (let [info (token/lookup-token client {:token "r00t"})]
        (is (= "r00t" (:id info)))
        (is (pos-int? (:creation-time info)))
        (is (= info (token/lookup-token client {}))
            "self lookup should return same info")
        (is (= (assoc info :id "")
               (token/lookup-token client {:accessor (:accessor info)}))
            "accessor lookup should return info without id")))))


(deftest ^:integration http-api
  (with-dev-server
    (cli "write" "auth/token/roles/test" "renewable=false" "orphan=true" "token_explicit_max_ttl=5m")
    (let [tokens (atom {})]
      (testing "create-token!"
        (testing "directly"
          (let [auth (token/create-token!
                       client
                       {:meta {:foo "bar"}
                        :policies ["default"]})]
            (swap! tokens assoc :default auth)
            (is (string? (:accessor auth)))
            (is (string? (:client-token auth)))
            (is (pos-int? (:lease-duration auth)))
            (is (= {:foo "bar"} (:metadata auth)))
            (is (= ["default"] (:policies auth)))
            (is (false? (:orphan auth)))
            (is (true? (:renewable auth)))))
        (testing "as orphan"
          (let [auth (token/create-orphan-token!
                       client
                       {:meta {:abc "def"}
                        :policies ["default"]
                        :renewable false})]
            (swap! tokens assoc :orphan auth)
            (is (string? (:accessor auth)))
            (is (string? (:client-token auth)))
            (is (pos-int? (:lease-duration auth)))
            (is (= {:abc "def"} (:metadata auth)))
            (is (= ["default"] (:policies auth)))
            (is (true? (:orphan auth)))
            (is (false? (:renewable auth)))))
        (testing "with role"
          (let [auth (token/create-role-token!
                       client
                       "test"
                       {:policies ["default"]
                        :renewable false})]
            (swap! tokens assoc :role auth)
            (is (string? (:accessor auth)))
            (is (string? (:client-token auth)))
            (is (= 300 (:lease-duration auth)))
            (is (= ["default"] (:policies auth)))
            (is (true? (:orphan auth)))
            (is (false? (:renewable auth))))))
      (testing "lookup-token"
        (testing "self"
          (let [auth (:default @tokens)
                client (test-client (:client-token auth))
                info (token/lookup-token client {})]
            (is (map? info))
            (is (= (:client-token auth) (:id info)))
            (is (= (:accessor auth) (:accessor info)))
            (is (= (:policies auth) (:policies info)))
            (is (<= (dec (:lease-duration auth)) (:ttl info)))
            (is (= (:metadata auth) (:meta info)))))
        (testing "with token"
          (let [auth (:orphan @tokens)
                info (token/lookup-token client {:token (:client-token auth)})]
            (is (map? info))
            (is (= (:client-token auth) (:id info)))
            (is (= (:accessor auth) (:accessor info)))
            (is (= (:policies auth) (:policies info)))
            (is (<= (dec (:lease-duration auth)) (:ttl info)))
            (is (= (:metadata auth) (:meta info)))))
        (testing "with accessor"
          (let [auth (:role @tokens)
                info (token/lookup-token client {:accessor (:accessor auth)})]
            (is (map? info))
            (is (= "" (:id info)))
            (is (= (:accessor auth) (:accessor info)))
            (is (= (:policies auth) (:policies info)))
            (is (<= (dec (:lease-duration auth)) (:ttl info)))
            (is (= (:metadata auth) (:meta info))))))
      (testing "renew-token!"
        (testing "self"
          (let [auth (:default @tokens)
                client (test-client (:client-token auth))
                info (token/renew-token! client {})]
            (is (= (dissoc auth :lease-duration)
                   (dissoc info :lease-duration)))))
        (testing "with token"
          (let [auth (:default @tokens)
                info (token/renew-token! client {:token (:client-token auth)})]
            (is (= (dissoc auth :lease-duration)
                   (dissoc info :lease-duration)))))
        (testing "with accessor"
          (let [auth (:default @tokens)
                info (token/renew-token! client {:accessor (:accessor auth)})]
            (is (= (-> auth
                       (assoc :client-token "")
                       (dissoc :lease-duration))
                   (dissoc info :lease-duration))))))
      (testing "revoke-token!"
        (testing "self"
          (let [auth (:role @tokens)
                result (token/revoke-token!
                         (test-client (:client-token auth))
                         {})]
            (is (nil? result))
            (is (thrown-with-msg? Exception #"bad token"
                  (token/lookup-token client {:token (:client-token auth)})))))
        (testing "with token"
          (let [auth (:orphan @tokens)
                result (token/revoke-token! client {:token (:client-token auth)})]
            (is (nil? result))
            (is (thrown-with-msg? Exception #"bad token"
                  (token/lookup-token client {:token (:client-token auth)})))))
        (testing "with accessor"
          (let [auth (:default @tokens)
                result (token/revoke-token! client {:accessor (:accessor auth)})]
            (is (nil? result))
            (is (thrown-with-msg? Exception #"bad token"
                  (token/lookup-token client {:token (:client-token auth)})))))))))
