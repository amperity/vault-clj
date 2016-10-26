(ns vault.cache-test
  (:require
    [clojure.test :refer :all]
    [vault.cache :as cache]))


(defmacro with-time
  "Evaluates the given body of forms with `vault.cache/now` rebound to always
  give the result `t`."
  [t & body]
  `(with-redefs [vault.cache/now (constantly ~t)]
     ~@body))


(deftest missing-info
  (let [c (cache/new-cache)]
    (is (nil? (cache/lookup c :foo))
        "lookup of unstored key should return nil")
    (is (nil? (cache/store! c :foo nil))
        "storing nil should return nil")
    (is (nil? (cache/lookup c :foo))
        "lookup of nil store should return nil")))


(deftest secret-expiry
  (let [c (cache/new-cache)]
    (with-time 1000
      (is (= {:lease-id "12345"
              :lease-duration 100
              :renewable true
              :expiry 101000
              :data {:bar "baz"}}
             (cache/store! c :foo {:lease_id "12345"
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
             (cache/lookup c :foo))
          "lookup of stored secret within expiry should return data structure"))
    (with-time 101001
      (is (nil? (cache/lookup c :foo))
          "lookup of stored secret after expiry should return nil"))))


(deftest secret-invalidation
  (let [c (cache/new-cache)]
    (is (some? (cache/store! c :bar {:data {:baz "qux"}})))
    (is (some? (cache/lookup c :bar)))
    (is (nil? (cache/invalidate! c :bar)))
    (is (nil? (cache/lookup c :bar))
        "lookup of invalidated secret should return nil")))
