(ns vault.lease-test
  (:require
    [clojure.test :refer :all]
    [vault.lease :as lease]))


(defmacro with-time
  "Evaluates the given body of forms with `vault.lease/now` rebound to always
  give the result `t`."
  [t & body]
  `(with-redefs [vault.lease/now (constantly ~t)]
     ~@body))


(deftest missing-info
  (let [c (lease/new-store)]
    (is (nil? (lease/lookup c :foo))
        "lookup of unstored key should return nil")
    (is (nil? (lease/update! c nil))
        "storing nil should return nil")
    (is (nil? (lease/lookup c :foo))
        "lookup of nil store should return nil")))


#_
(deftest secret-expiry
  (let [c (lease/new-store)]
    (with-time 1000
      (is (= {:lease-id "12345"
              :lease-duration 100
              :renewable true
              :expiry 101000
              :data {:bar "baz"}}
             (lease/update! c {:lease_id "12345"
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
             (lease/lookup c :foo))
          "lookup of stored secret within expiry should return data structure"))
    (with-time 101001
      (is (nil? (lease/lookup c :foo))
          "lookup of stored secret after expiry should return nil"))))


#_
(deftest secret-invalidation
  (let [c (lease/new-store)]
    (is (some? (lease/update! c {:path :bar, :data {:baz "qux"}})))
    (is (some? (lease/lookup c :bar)))
    (is (nil? (lease/remove-path! c :bar)))
    (is (nil? (lease/lookup c :bar))
        "lookup of invalidated secret should return nil")))
