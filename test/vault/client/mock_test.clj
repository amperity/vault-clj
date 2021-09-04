(ns vault.client.mock-test
  (:require
    [clojure.java.io :as io]
    [clojure.test :refer [deftest testing is]]
    [vault.client :as vault]
    [vault.client.mock]))


(deftest authentication
  (let [client (vault/new-client "mock:-")]
    (testing "with bad input"
      (is (thrown-with-msg? IllegalArgumentException #"Client authentication must be a map"
            (vault/authenticate! client [])))
      (is (thrown-with-msg? IllegalArgumentException #"containing a client-token"
            (vault/authenticate! client {}))))
    (testing "with token string"
      (is (nil? (vault/authenticate! client "t0p-53cr3t")))
      (is (= "t0p-53cr3t" (get-in @(:memory client) [:auth :client-token]))))
    (testing "with auth info"
      (is (nil? (vault/authenticate! client {:client-token "t0p-53cr3t"
                                             :ttl 12345})))
      (is (= {:client-token "t0p-53cr3t"
              :ttl 12345}
             (:auth @(:memory client)))))))


(deftest client-constructor
  (testing "with bad scheme"
    (is (thrown-with-msg? IllegalArgumentException #"Unsupported Vault address scheme"
          (vault/new-client "mook:abc"))))
  (testing "without fixture"
    (let [client (vault/new-client "mock:-")]
      (is (satisfies? vault/Client client))
      (is (= {} @(:memory client)))))
  (testing "with missing fixture"
    (let [client (vault/new-client "mock:target/test/fixture.edn")]
      (is (satisfies? vault/Client client))
      (is (= {} @(:memory client)))))
  (testing "with fixture resource"
    (let [fixture (io/file "test/vault/client/fixture.edn")]
      (.deleteOnExit fixture)
      (try
        (spit fixture "{:secrets {\"kv\" {}}}")
        (let [client (vault/new-client "mock:vault/client/fixture.edn")]
          (is (satisfies? vault/Client client))
          (is (= {:secrets {"kv" {}}}
                 @(:memory client))))
        (finally
          (io/delete-file fixture :gone)))))
  (testing "with fixture file"
    (let [fixture (io/file "target/test/fixture.edn")]
      (.deleteOnExit fixture)
      (try
        (io/make-parents fixture)
        (spit fixture "{:secrets {\"kv\" {}}}")
        (let [client (vault/new-client "mock:target/test/fixture.edn")]
          (is (satisfies? vault/Client client))
          (is (= {:secrets {"kv" {}}}
                 @(:memory client))))
        (finally
          (io/delete-file fixture :gone))))))
