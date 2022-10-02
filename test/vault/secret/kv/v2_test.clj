(ns vault.secret.kv.v2-test
  (:require
    [clojure.test :refer [is testing deftest]]
    [vault.client.mock :refer [mock-client]]
    [vault.integration :refer [with-dev-server cli]]
    [vault.secret.kv.v2 :as kv2]
    [vault.util :as u])
  (:import
    java.time.Instant))


(deftest mock-api
  (let [client (mock-client)
        t1 (Instant/parse "2022-09-27T16:52:18Z")
        t2 (Instant/parse "2022-09-28T01:10:23Z")
        t3 (Instant/parse "2022-09-28T08:32:57Z")]
    (testing "mount"
      (is (nil? (::kv2/mount client))
          "default should be nil")
      (let [client' (kv2/with-mount client "kv")]
        (is (= "kv" (::kv2/mount client'))
            "with-mount sets override")
        (is (nil? (::kv2/mount (kv2/with-mount client' nil)))
            "with nil unsets override")))
    (testing "write-secret!"
      (testing "basic writes"
        (u/with-now t1
          (is (= {:created-time t1
                  :destroyed false
                  :version 1}
                 (kv2/write-secret! client "test/foo/alpha" {:one :two, :three 456, :seven true})))
          (is (= {:created-time t1
                  :destroyed false
                  :version 1}
                 (kv2/write-secret! client "test/foo/beta" {:xyz #{"abc"}})))
          (is (= {:created-time t1
                  :destroyed false
                  :version 1}
                 (kv2/write-secret! client "test/gamma" {:map {:a 1, :b 2}})))))
      (testing "multiple versions"
        (u/with-now t2
          (is (= {:created-time t2
                  :destroyed false
                  :version 2}
                 (kv2/write-secret! client "test/foo/beta" {:xyz ["abc" "def"]})))))
      (testing "compare-and-set"
        (u/with-now t2
          (testing "with mismatched version"
            (is (thrown? Exception
                  (kv2/write-secret! client "test/foo/beta" {:xyz ["ghi"]} {:cas 1}))
                "should reject write"))
          (testing "with matching version"
            (is (= {:created-time t2
                    :destroyed false
                    :version 2}
                   (kv2/write-secret! client "test/foo/alpha" {:one :three, :four 5} {:cas 1}))
                "should write new version")))))
    (testing "list-secrets"
      (is (nil? (kv2/list-secrets client "foo/"))
          "should return nil on nonexistent prefix")
      (is (nil? (kv2/list-secrets client "test/foo/alpha"))
          "should return nil on secret path")
      (is (= {:keys ["test/"]} (kv2/list-secrets client "/")))
      (is (= {:keys ["foo/" "gamma"]} (kv2/list-secrets client "test")))
      (is (= {:keys ["alpha" "beta"]} (kv2/list-secrets client "/test/foo/"))))
    (testing "read-secret"
      (is (= {:one "three", :four 5}
             (kv2/read-secret client "test/foo/alpha")))
      (is (= {:xyz ["abc" "def"]}
             (kv2/read-secret client "test/foo/beta")))
      (is (= {:map {:a 1, :b 2}}
             (kv2/read-secret client "test/gamma")))
      (testing "on nonexistent path"
        (is (thrown-with-msg? Exception #"No kv-v2 secret found at secret:foo/bar"
              (kv2/read-secret client "foo/bar")))
        (is (thrown-with-msg? Exception #"No kv-v2 secret found at secret:alt/test"
              (kv2/read-secret client "alt/test")))
        (is (= :gone (kv2/read-secret client "alt/test" {:not-found :gone}))))
      (testing "for specific version"
        (is (= {:xyz ["abc"]}
               (kv2/read-secret client "test/foo/beta" {:version 1})))
        (is (thrown? Exception
              (kv2/read-secret client "test/foo/beta" {:version 3})))))
    (testing "patch-secret!"
      (testing "on nonexistent secret"
        (is (thrown-with-msg? Exception #"No kv-v2 secret found at secret:foo/bar"
              (kv2/patch-secret! client "foo/bar" {:foo "bar"}))
            "should throw an error"))
      (testing "on extant secret"
        (u/with-now t2
          (is (= {:created-time t2
                  :destroyed false
                  :version 2}
                 (kv2/patch-secret! client "test/gamma" {:map {:b nil, :c 3}, :x false}))))
        (is (= {:map {:a 1, :c 3}, :x false}
               (kv2/read-secret client "test/gamma"))))
      (testing "compare-and-set"
        (u/with-now t3
          (testing "with mismatched version"
            (is (thrown? Exception
                  (kv2/patch-secret! client "test/gamma" {:y true} {:cas 1}))
                "should reject write"))
          (testing "with matching version"
            (is (= {:created-time t3
                    :destroyed false
                    :version 3}
                   (kv2/patch-secret! client "test/gamma" {:y true} {:cas 2}))
                "should write new version")))))
    (testing "metadata"
      (testing "for nonexistent secret"
        (is (thrown-with-msg? Exception #"No kv-v2 secret found at secret:foo/bar"
              (kv2/read-metadata client "foo/bar"))
            "read should throw not-found error")
        (is (thrown-with-msg? Exception #"No kv-v2 secret found at secret:foo/bar"
              (kv2/write-metadata! client "foo/bar" {:x 123}))
            "write should throw not-found error")
        (is (thrown-with-msg? Exception #"No kv-v2 secret found at secret:foo/bar"
              (kv2/patch-metadata! client "foo/bar" {:x 123}))
            "patch should throw not-found error"))
      (testing "read extant secret"
        (is (= {:cas-required false
                :created-time t1
                :current-version 3
                :delete-version-after "0s"
                :max-versions 0
                :oldest-version 1
                :updated-time t3
                :versions {1 {:created-time t1, :destroyed false}
                           2 {:created-time t2, :destroyed false}
                           3 {:created-time t3, :destroyed false}}}
               (kv2/read-metadata client "test/gamma"))))
      (testing "write metadata"
        (is (nil? (kv2/write-metadata!
                    client "test/gamma"
                    {:delete-version-after "5m0s"
                     :max-versions 5})))
        (is (nil? (kv2/write-metadata!
                    client "test/gamma"
                    {:custom-metadata {:foo/bar "baz", :q 123}})))
        (is (= {:cas-required false
                :created-time t1
                :current-version 3
                :delete-version-after "5m0s"
                :max-versions 5
                :oldest-version 1
                :updated-time t3
                :custom-metadata {:foo/bar "baz", :q "123"}
                :versions {1 {:created-time t1, :destroyed false}
                           2 {:created-time t2, :destroyed false}
                           3 {:created-time t3, :destroyed false}}}
               (kv2/read-metadata client "test/gamma"))))
      (testing "patch metadata"
        (is (nil? (kv2/patch-metadata!
                    client "test/gamma"
                    {:max-versions 5
                     :custom-metadata {:foo/bar "baz", :q 123}})))
        (is (nil? (kv2/patch-metadata!
                    client "test/gamma"
                    {:delete-version-after "5m0s"})))
        (is (= {:cas-required false
                :created-time t1
                :current-version 3
                :delete-version-after "5m0s"
                :max-versions 5
                :oldest-version 1
                :updated-time t3
                :custom-metadata {:foo/bar "baz", :q "123"}
                :versions {1 {:created-time t1, :destroyed false}
                           2 {:created-time t2, :destroyed false}
                           3 {:created-time t3, :destroyed false}}}
               (kv2/read-metadata client "test/gamma"))))
      (testing "on read"
        (is (= {:foo/bar "baz", :q "123"}
               (::kv2/custom-metadata (meta (kv2/read-secret client "test/gamma")))))))
    (testing "delete-secret!"
      (is (nil? (kv2/delete-secret! client "foo/bar"))
          "should return nil on missing secret")
      (is (nil? (kv2/delete-secret! client "test/foo/alpha")))
      (is (= {:keys ["foo/" "gamma"]} (kv2/list-secrets client "test")))
      (is (= :deleted (kv2/read-secret client "test/foo/alpha" {:not-found :deleted}))))
    (testing "delete-versions!"
      (u/with-now t3
        (is (nil? (kv2/delete-versions! client "foo/bar" [1]))
            "should return nil on missing secret")
        (is (nil? (kv2/delete-versions! client "test/gamma" [1 2 8])))
        (is (= {1 {:created-time t1, :deletion-time t3, :destroyed false}
                2 {:created-time t2, :deletion-time t3, :destroyed false}
                3 {:created-time t3, :destroyed false}}
               (:versions (kv2/read-metadata client "test/gamma"))))))
    (testing "undelete-versions!"
      (is (nil? (kv2/undelete-versions! client "foo/bar" [1]))
          "should return nil on missing secret")
      (is (nil? (kv2/undelete-versions! client "test/gamma" [2 4])))
      (is (= {1 {:created-time t1, :deletion-time t3, :destroyed false}
              2 {:created-time t2, :destroyed false}
              3 {:created-time t3, :destroyed false}}
             (:versions (kv2/read-metadata client "test/gamma")))))
    (testing "destroy-versions!"
      (is (nil? (kv2/destroy-versions! client "foo/not-here" [1]))
          "should return nil on missing secret")
      (is (nil? (kv2/destroy-versions! client "test/gamma" [2 8])))
      (is (= {1 {:created-time t1, :deletion-time t3, :destroyed false}
              2 {:created-time t2, :destroyed true}
              3 {:created-time t3, :destroyed false}}
             (:versions (kv2/read-metadata client "test/gamma")))))
    (testing "destroy-secret!"
      (is (nil? (kv2/destroy-secret! client "foo/not-here"))
          "should return nil on missing secret")
      (is (nil? (kv2/destroy-secret! client "test/gamma")))
      (is (thrown-with-msg? Exception #"No kv-v2 secret found at secret:test/gamma"
            (kv2/read-metadata client "test/gamma"))))))


(deftest ^:integration http-api
  (with-dev-server
    (testing "mount"
      (is (nil? (::kv2/mount client))
          "default should be nil")
      (let [client' (kv2/with-mount client "kv")]
        (is (= "kv" (::kv2/mount client'))
            "with-mount sets override")
        (is (nil? (::kv2/mount (kv2/with-mount client' nil)))
            "with nil unsets override")))
    ,,,))
