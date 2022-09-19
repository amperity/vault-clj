(ns vault.client.mock-test
  (:require
    [clojure.java.io :as io]
    [clojure.test :refer [deftest testing is]]
    [vault.auth :as auth]
    [vault.client.mock :as mock]
    [vault.client.proto :as proto]))


(deftest authentication
  (let [client (mock/mock-client "mock:-")]
    (testing "with bad input"
      (is (thrown-with-msg? IllegalArgumentException #"Client authentication must be a map"
            (proto/authenticate! client [])))
      (is (thrown-with-msg? IllegalArgumentException #"containing a client-token"
            (proto/authenticate! client {}))))
    (testing "with token string"
      (is (identical? client (proto/authenticate! client "t0p-53cr3t")))
      (is (= {::auth/client-token "t0p-53cr3t"}
             (proto/auth-info client))))
    (testing "with auth info"
      (is (identical? client (proto/authenticate!
                               client
                               {::auth/client-token "t0p-53cr3t"
                                ::auth/lease-duration 12345})))
      (is (= {::auth/client-token "t0p-53cr3t"
              ::auth/lease-duration 12345}
             (proto/auth-info client))))))


(deftest client-constructor
  (testing "with bad scheme"
    (is (thrown-with-msg? IllegalArgumentException #"Mock client must be constructed with a map of data or a URN with scheme 'mock'"
          (mock/mock-client "mook:abc"))))
  (testing "without fixture"
    (let [client (mock/mock-client "mock:-")]
      (is (satisfies? proto/Client client))
      (is (= {} @(:memory client)))))
  (testing "with missing fixture"
    (let [client (mock/mock-client "mock:target/test/fixture.edn")]
      (is (satisfies? proto/Client client))
      (is (= {} @(:memory client)))))
  (testing "with fixture resource"
    (let [fixture (io/file "test/vault/client/fixture.edn")]
      (.deleteOnExit fixture)
      (try
        (spit fixture "{:secrets {\"kv\" {}}}")
        (let [client (mock/mock-client "mock:vault/client/fixture.edn")]
          (is (satisfies? proto/Client client))
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
        (let [client (mock/mock-client "mock:target/test/fixture.edn")]
          (is (satisfies? proto/Client client))
          (is (= {:secrets {"kv" {}}}
                 @(:memory client))))
        (finally
          (io/delete-file fixture :gone))))))
