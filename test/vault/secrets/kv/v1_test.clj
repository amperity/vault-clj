(ns vault.secrets.kv.v1-test
  (:require
    [clojure.test :refer [is testing deftest]]
    [vault.client.mock :refer [mock-client]]
    [vault.integration :refer [with-dev-server]]
    [vault.secrets.kv.v1 :as kv1]))


(deftest mock-api
  (let [client (mock-client)]
    (testing "write-secret!"
      (testing "with default mount"
        (is (nil? (kv1/write-secret! client "test/foo/alpha" {:one :two, :three 456, :seven true})))
        (is (nil? (kv1/write-secret! client "test/foo/beta" {:xyz #{"abc"}})))
        (is (nil? (kv1/write-secret! client "test/gamma" {:map {:a 1, :b 2}}))))
      (testing "with alternate mount"
        (is (nil? (kv1/write-secret! client "kv:alt/test" {:some "thing"})))))
    (testing "list-secrets"
      (testing "with default mount"
        (is (nil? (kv1/list-secrets client "foo/"))
            "should return nil on nonexistent prefix")
        (is (nil? (kv1/list-secrets client "test/foo/alpha"))
            "should return nil on secret path")
        (is (= ["test/"] (kv1/list-secrets client "/")))
        (is (= ["foo/" "gamma"] (kv1/list-secrets client "test")))
        (is (= ["alpha" "beta"] (kv1/list-secrets client "/test/foo/"))))
      (testing "with alternate mount"
        (is (= ["test"] (kv1/list-secrets client "kv:alt")))))
    (testing "read-secret"
      (testing "with default mount"
        (is (= {:one "two", :three 456, :seven true}
               (kv1/read-secret client "test/foo/alpha")))
        (is (= {:xyz ["abc"]}
               (kv1/read-secret client "test/foo/beta")))
        (is (= {:map {:a 1, :b 2}}
               (kv1/read-secret client "test/gamma")))
        (testing "on nonexistent path"
          (is (thrown-with-msg? Exception #"No kv-v1 secret found at secret:foo/bar"
                (kv1/read-secret client "foo/bar")))
          (is (thrown-with-msg? Exception #"No kv-v1 secret found at secret:alt/test"
                (kv1/read-secret client "alt/test")))
          (is (= :gone (kv1/read-secret client "alt/test" {:not-found :gone})))))
      (testing "with alternate mount"
        (is (= {:some "thing"}
               (kv1/read-secret client "kv:alt/test")))
        (is (thrown-with-msg? Exception #"No kv-v1 secret found at kv:foo/bar"
              (kv1/read-secret client "kv:foo/bar")))
        (is (= :shrug (kv1/read-secret client "kv:test/foo/alpha" {:not-found :shrug})))))
    (testing "write-secret! update"
      (is (nil? (kv1/write-secret! client "test/foo/beta" {:qrs false})))
      (is (= {:qrs false} (kv1/read-secret client "test/foo/beta"))
          "should overwrite previous secret"))
    (testing "delete-secret!"
      (is (nil? (kv1/delete-secret! client "test/gamma")))
      (is (= ["foo/"] (kv1/list-secrets client "test")))
      (is (= :deleted (kv1/read-secret client "test/gamma" {:not-found :deleted}))))))


;; TODO: http-api integration test
