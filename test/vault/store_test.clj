(ns vault.store-test
  (:require
    [clojure.test :refer :all]
    [vault.store :as store]))


(defmacro with-time
  "Evaluates the given body of forms with `vault.store/now` rebound to always
  give the result `t`."
  [t & body]
  `(with-redefs [vault.store/now (constantly ~t)]
     ~@body))


(deftest missing-info
  (let [c (store/new-store)]
    (is (nil? (store/lookup c :foo))
        "lookup of unstored key should return nil")
    (is (nil? (store/store! c :foo nil))
        "storing nil should return nil")
    (is (nil? (store/lookup c :foo))
        "lookup of nil store should return nil")))


(deftest secret-expiry
  (let [c (store/new-store)]
    (with-time 1000
      (is (= {:lease-id "12345"
              :lease-duration 100
              :renewable true
              :expiry 101000
              :data {:bar "baz"}}
             (store/store! c :foo {:lease_id "12345"
                                   :lease_duration 100
                                   :renewable true
                                   :data {:bar "baz"}}))
          "storing secret info should return data structure"))
    (with-time 50000
      (is (= {:lease-id "12345"
              :lease-duration 100
              :renewable true
              :expiry 101000
              :data {:bar "baz"}}
             (store/lookup c :foo))
          "lookup of stored secret within expiry should return data structure"))
    (with-time 101001
      (is (nil? (store/lookup c :foo))
          "lookup of stored secret after expiry should return nil"))))


(deftest secret-invalidation
  (let [c (store/new-store)]
    (is (some? (store/store! c :bar {:data {:baz "qux"}})))
    (is (some? (store/lookup c :bar)))
    (is (nil? (store/invalidate! c :bar)))
    (is (nil? (store/lookup c :bar))
        "lookup of invalidated secret should return nil")))
