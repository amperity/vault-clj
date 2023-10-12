(ns vault.secret.kv.v1-test
  (:require
    [clojure.test :refer [is testing deftest]]
    [vault.client.mock :refer [mock-client]]
    [vault.integration :refer [with-dev-server cli]]
    [vault.lease :as lease]
    [vault.secret.kv.v1 :as kv1]
    [vault.util :as u]))


(deftest mock-api
  (let [client (mock-client)]
    (testing "write-secret!"
      (testing "with default mount"
        (is (nil? (::kv1/mount client)))
        (is (nil? (kv1/write-secret! client "test/foo/alpha" {:one :two, :three 456, :seven true})))
        (is (nil? (kv1/write-secret! client "test/foo/beta" {:xyz #{"abc"}})))
        (is (nil? (kv1/write-secret! client "test/gamma" {:map {:a 1, :b 2}}))))
      (testing "with alternate mount"
        (let [client' (kv1/with-mount client "kv")]
          (is (= "kv" (::kv1/mount client')))
          (is (nil? (::kv1/mount (kv1/with-mount client' nil))))
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
        (is (nil? (::kv1/mount client)))
        (is (nil? (kv1/write-secret! client "test/foo/alpha" {:one :two, :three 456, :seven true})))
        (is (nil? (kv1/write-secret! client "test/foo/beta" {:xyz #{"abc"}})))
        (is (nil? (kv1/write-secret! client "test/gamma" {:map {:a 1, :b 2}}))))
      (testing "with alternate mount"
        (let [client' (kv1/with-mount client "kv")]
          (is (= "kv" (::kv1/mount client')))
          (is (nil? (::kv1/mount (kv1/with-mount client' nil))))
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
          (is (= :shrug (kv1/read-secret client' "test/foo/alpha" {:not-found :shrug})))))
      (testing "lease caching"
        (let [cache-key [::kv1/secret "secret" "test/foo/beta"]]
          (lease/invalidate! client cache-key)
          (testing "with zero ttl"
            (let [result (kv1/read-secret client "test/foo/beta" {:ttl 0})]
              (is (= {:xyz ["abc"]} result))
              (is (not (:vault.client/cached? (meta result)))
                  "should read a new result")
              (is (nil? (lease/find-data client cache-key))
                  "should not cache data")))
          (testing "with positive ttl"
            (is (= {:xyz ["abc"]}
                   (kv1/read-secret client "test/foo/beta" {:ttl 300})))
            (is (= {:xyz ["abc"]} (lease/find-data client cache-key))
                "should cache data")
            (let [result (kv1/read-secret client "test/foo/beta" {:ttl 300})]
              (is (:vault.client/cached? (meta result))
                  "should read cached result")))
          (testing "with refresh option"
            (let [result (kv1/read-secret client "test/foo/beta" {:refresh? true})]
              (is (not (:vault.client/cached? (meta result)))
                  "should read a new result")
              (is (= 1 (count (filter #(= cache-key (::lease/key (val %)))
                                      @(u/unveil (:leases client)))))
                  "should replace old lease"))))))
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
