(ns vault.lease-test
  (:require
    [clojure.test :refer :all]
    [vault.lease :as lease])
  (:import
    java.time.Instant))


(defmacro with-time
  "Evaluates the given body of forms with `vault.lease/now` rebound to always
  give the result `t`."
  [t & body]
  `(with-redefs [vault.lease/now (constantly (Instant/ofEpochMilli ~t))]
     ~@body))


(deftest missing-info
  (let [c (lease/new-store)]
    (is (nil? (lease/lookup c :foo))
        "lookup of unstored key should return nil")
    (is (nil? (lease/update! c nil))
        "storing nil should return nil")
    (is (nil? (lease/lookup c :foo))
        "lookup of nil store should return nil")))


(deftest secret-expiry
  (let [c (lease/new-store)]
    (with-time 1000
      (is (= {:path "foo/bar"
              :data {:bar "baz"}
              :lease-id "foo/bar/12345"
              :lease-duration 100
              :renewable true
              :vault.lease/issued (Instant/ofEpochMilli   1000)
              :vault.lease/expiry (Instant/ofEpochMilli 101000)}
             (lease/update! c {:path "foo/bar"
                               :lease-id "foo/bar/12345"
                               :lease-duration 100
                               :renewable true
                               :data {:bar "baz"}}))
          "storing secret info should return data structure"))
    (with-time 50000
      (is (= {:path "foo/bar"
              :data {:bar "baz"}
              :lease-id "foo/bar/12345"
              :lease-duration 100
              :renewable true
              :vault.lease/issued (Instant/ofEpochMilli   1000)
              :vault.lease/expiry (Instant/ofEpochMilli 101000)}
             (lease/lookup c "foo/bar"))
          "lookup of stored secret within expiry should return data structure"))
    (with-time 101001
      (is (lease/expired? (lease/lookup c "foo/bar"))
          "lookup of stored secret after expiry should return nil"))))


(deftest secret-invalidation
  (let [c (lease/new-store)]
    (is (some? (lease/update! c {:path "foo/bar"
                                 :data {:baz "qux"}
                                 :lease-id "foo/bar/12345"})))
    (is (some? (lease/lookup c "foo/bar")))
    (is (nil? (lease/remove-path! c "foo/bar")))
    (is (nil? (lease/lookup c "foo/bar"))
        "lookup of invalidated secret should return nil")))
