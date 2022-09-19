(ns vault.secret.kv.v1-test
  (:require
    [clojure.test :refer [is testing deftest]]
    [vault.client.mock :refer [mock-client]]
    [vault.integration :refer [with-dev-server cli]]
    [vault.secret.kv.v1 :as kv1]))


(deftest mock-api
  (let [client (mock-client)]
    (testing "write-secret!"
      (testing "with default mount"
        (is (nil? (kv1/write-secret! client "test/foo/alpha" {:one :two, :three 456, :seven true})))
        (is (nil? (kv1/write-secret! client "test/foo/beta" {:xyz #{"abc"}})))
        (is (nil? (kv1/write-secret! client "test/gamma" {:map {:a 1, :b 2}}))))
      (testing "with alternate mount"
        (let [client' (kv1/with-mount client "kv")]
          (is (nil? (kv1/write-secret! client' "alt/test" {:some "thing"}))))))
    (testing "list-secrets"
      (testing "with default mount"
        (is (nil? (kv1/list-secrets client "foo/"))
            "should return nil on nonexistent prefix")
        (is (nil? (kv1/list-secrets client "test/foo/alpha"))
            "should return nil on secret path")
        (is (= {:keys ["test/"]} (kv1/list-secrets client "/")))
        (is (= {:keys ["foo/" "gamma"]} (kv1/list-secrets client "test")))
        (is (= {:keys ["alpha" "beta"]} (kv1/list-secrets client "/test/foo/"))))
      (testing "with alternate mount"
        (let [client' (kv1/with-mount client "kv")]
          (is (= {:keys ["test"]} (kv1/list-secrets client' "alt"))))))
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
        (let [client' (kv1/with-mount client "kv")]
          (is (= {:some "thing"}
                 (kv1/read-secret client' "alt/test")))
          (is (thrown-with-msg? Exception #"No kv-v1 secret found at kv:foo/bar"
                (kv1/read-secret client' "foo/bar")))
          (is (= :shrug (kv1/read-secret client' "test/foo/alpha" {:not-found :shrug}))))))
    (testing "write-secret! update"
      (is (nil? (kv1/write-secret! client "test/foo/beta" {:qrs false})))
      (is (= {:qrs false} (kv1/read-secret client "test/foo/beta"))
          "should overwrite previous secret"))
    (testing "delete-secret!"
      (is (nil? (kv1/delete-secret! client "test/gamma")))
      (is (= {:keys ["foo/"]} (kv1/list-secrets client "test")))
      (is (= :deleted (kv1/read-secret client "test/gamma" {:not-found :deleted}))))))


(deftest ^:integration http-api
  (with-dev-server
    (cli "secrets" "disable" "secret/")
    (cli "secrets" "enable" "-path=secret" "-version=1" "kv")
    (cli "secrets" "enable" "-path=kv" "-version=1" "kv")
    (testing "write-secret!"
      (testing "with default mount"
        (is (nil? (kv1/write-secret! client "test/foo/alpha" {:one :two, :three 456, :seven true})))
        (is (nil? (kv1/write-secret! client "test/foo/beta" {:xyz #{"abc"}})))
        (is (nil? (kv1/write-secret! client "test/gamma" {:map {:a 1, :b 2}}))))
      (testing "with alternate mount"
        (let [client' (kv1/with-mount client "kv")]
          (is (nil? (kv1/write-secret! client' "alt/test" {:some "thing"}))))))
    (testing "list-secrets"
      (testing "with default mount"
        (is (nil? (kv1/list-secrets client "foo/"))
            "should return nil on nonexistent prefix")
        (is (nil? (kv1/list-secrets client "test/foo/alpha"))
            "should return nil on secret path")
        (is (= {:keys ["test/"]} (kv1/list-secrets client "/")))
        (is (= {:keys ["foo/" "gamma"]} (kv1/list-secrets client "test")))
        (is (= {:keys ["alpha" "beta"]} (kv1/list-secrets client "/test/foo/"))))
      (testing "with alternate mount"
        (let [client' (kv1/with-mount client "kv")]
          (is (= {:keys ["test"]} (kv1/list-secrets client' "alt"))))))
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
        (let [client' (kv1/with-mount client "kv")]
          (is (= {:some "thing"}
                 (kv1/read-secret client' "alt/test")))
          (is (thrown-with-msg? Exception #"No kv-v1 secret found at kv:foo/bar"
                (kv1/read-secret client' "foo/bar")))
          (is (= :shrug (kv1/read-secret client' "test/foo/alpha" {:not-found :shrug}))))))
    (testing "write-secret! update"
      (is (nil? (kv1/write-secret! client "test/foo/beta" {:qrs false})))
      (is (= {:qrs false} (kv1/read-secret client "test/foo/beta"))
          "should overwrite previous secret"))
    (testing "delete-secret!"
      (is (nil? (kv1/delete-secret! client "test/gamma")))
      (is (= {:keys ["foo/"]} (kv1/list-secrets client "test")))
      (is (= :deleted (kv1/read-secret client "test/gamma" {:not-found :deleted}))))
    (testing "invalid mounts"
      (is (thrown-with-msg? Exception #"no handler"
            (kv1/list-secrets (kv1/with-mount client "wat") "foo/bar")))
      (is (thrown-with-msg? Exception #"no handler"
            (kv1/read-secret (kv1/with-mount client "wat") "foo/bar/baz"))))))
