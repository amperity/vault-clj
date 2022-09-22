(ns vault.lease-test
  (:require
    [clojure.spec.alpha :as s]
    [clojure.test :refer [deftest testing is]]
    [vault.lease :as lease]
    [vault.util :as u])
  (:import
    java.time.Instant))


(deftest spec-validation
  (testing "on-renew"
    (is (s/valid? ::lease/on-renew []))
    (is (s/valid? ::lease/on-renew [inc]))
    (is (not (s/valid? ::lease/on-renew "foo")))
    (is (not (s/valid? ::lease/on-renew [123]))))
  (testing "on-rotate"
    (is (s/valid? ::lease/on-rotate []))
    (is (s/valid? ::lease/on-rotate [inc]))
    (is (not (s/valid? ::lease/on-rotate "foo")))
    (is (not (s/valid? ::lease/on-rotate [123]))))
  (testing "on-error"
    (is (s/valid? ::lease/on-error []))
    (is (s/valid? ::lease/on-error [inc]))
    (is (not (s/valid? ::lease/on-error "foo")))
    (is (not (s/valid? ::lease/on-error [123]))))
  (testing "info map"
    (is (not (s/valid? ::lease/info nil)))
    (is (not (s/valid? ::lease/info "foo")))
    (is (s/valid? ::lease/info {}))
    (is (s/valid? ::lease/info
                  #::lease
                  {:id "secret/foo/bar/123-456-789"
                   :duration 600
                   :renewable? false}))
    (is (s/valid? ::lease/info {::foo 123}))))


(deftest helper-predicates
  (testing "expired?"
    (u/with-now (Instant/parse "2022-09-21T16:00:00Z")
      (is (true? (lease/expired? {}))
          "lease without expiry should be considered expired")
      (is (true? (lease/expired? {::lease/expires-at (Instant/parse "2022-09-21T15:59:59Z")}))
          "lease with expiry in the past should be expired")
      (is (true? (lease/expired? {::lease/expires-at (Instant/parse "2022-09-21T16:00:00Z")}))
          "lease with expiry now should be expired")
      (is (false? (lease/expired? {::lease/expires-at (Instant/parse "2022-09-21T16:00:01Z")}))
          "lease with expiry in the future should not be expired")))
  (testing "expires-within?"
    (u/with-now (Instant/parse "2022-09-21T18:55:00Z")
      (let [lease {::lease/expires-at (Instant/parse "2022-09-21T19:00:00Z")}]
        (is (true? (lease/expires-within? {} 10))
            "lease without expiry should be considered expired")
        (is (true? (lease/expires-within? lease 600))
            "lease expiring within the window should return true")
        (is (false? (lease/expires-within? lease 60))
            "lease expiring after the window should return false")))))


(deftest get-lease
  (let [store (lease/new-store)
        lease-id "foo/bar/123"
        lease {::lease/id lease-id
               ::lease/expires-at (Instant/parse "2022-09-21T18:00:00Z")
               ::lease/data {:foo 123}}]
    (swap! store assoc lease-id lease)
    (testing "for current lease"
      (u/with-now (Instant/parse "2022-09-21T17:00:00Z")
        (is (= lease (lease/get-lease store lease-id))
            "should return lease")))
    (testing "for expired lease"
      (u/with-now (Instant/parse "2022-09-21T19:00:00Z")
        (is (nil? (lease/get-lease store lease-id))
            "should return nil")))
    (testing "for missing lease"
      (is (nil? (lease/get-lease store "foo/bar/456"))
          "should return nil"))))


(deftest find-lease-data
  (let [store (lease/new-store)
        lease-id "foo/bar/123"
        cache-key [:foo 123]
        lease {::lease/id lease-id
               ::lease/key cache-key
               ::lease/expires-at (Instant/parse "2022-09-21T18:00:00Z")
               ::lease/data {:foo 123}}]
    (swap! store assoc lease-id lease)
    (testing "for current lease"
      (u/with-now (Instant/parse "2022-09-21T17:00:00Z")
        (let [result (lease/find-data store cache-key)]
          (is (= {:foo 123} result)
              "should return lease data")
          (is (= (dissoc lease ::lease/data) (meta result))
              "should attach lease metadata"))))
    (testing "for expired lease"
      (u/with-now (Instant/parse "2022-09-21T19:00:00Z")
        (is (nil? (lease/find-data store cache-key))
            "should return nil")))
    (testing "for missing lease"
      (is (nil? (lease/find-data store [:foo 456]))
          "should return nil"))))


(deftest put-lease
  (testing "current lease"
    (let [store (lease/new-store)]
      (u/with-now (Instant/parse "2022-09-21T17:00:00Z")
        (is (= {:foo 123}
               (lease/put! store
                           {::lease/id "foo/bar/123"
                            ::lease/expires-at (Instant/parse "2022-09-21T18:00:00Z")}
                           {:foo 123}))
            "put should return data map")
        (is (= {"foo/bar/123"
                {::lease/id "foo/bar/123"
                 ::lease/expires-at (Instant/parse "2022-09-21T18:00:00Z")
                 ::lease/data {:foo 123}}}
               @store)
            "lease should be stored"))))
  (testing "expired lease"
    (let [store (lease/new-store)]
      (u/with-now (Instant/parse "2022-09-21T17:00:00Z")
        (is (= {:foo 456}
               (lease/put! store
                           {::lease/id "foo/bar/456"
                            ::lease/expires-at (Instant/parse "2022-09-21T16:00:00Z")}
                           {:foo 456}))
            "put should return data map")
        (is (empty? @store)
            "lease should not be stored")))))


(deftest update-lease
  (testing "missing lease"
    (let [store (lease/new-store)]
      (is (nil? (lease/update! store {::lease/id "foo/bar/123"})))
      (is (empty? @store))))
  (testing "present lease"
    (let [store (lease/new-store)
          lease-id "foo/bar/123"
          lease {::lease/id lease-id
                 ::lease/expires-at (Instant/parse "2022-09-21T18:00:00Z")
                 ::lease/data {:foo 123}}
          t2 (Instant/parse "2022-09-21T22:00:00Z")
          lease' (assoc lease ::lease/expires-at t2)]
      (swap! store assoc lease-id lease)
      (is (= lease' (lease/update! store {::lease/id lease-id, ::lease/expires-at t2})))
      (is (= lease' (first (vals @store)))))))


(deftest delete-lease
  (let [store (lease/new-store)
        lease-id "foo/bar/123"
        lease {::lease/id lease-id
               ::lease/expires-at (Instant/parse "2022-09-21T18:00:00Z")
               ::lease/data {:foo 123}}]
    (swap! store assoc lease-id lease)
    (is (nil? (lease/delete! store "foo/baz/000")))
    (is (= 1 (count @store)))
    (is (nil? (lease/delete! store lease-id)))
    (is (empty? @store))))


(deftest invalidate-lease
  (let [store (lease/new-store)
        lease-a-id "foo/bar/123"
        lease-a {::lease/id lease-a-id
                 ::lease/key [:foo 123]
                 ::lease/expires-at (Instant/parse "2022-09-21T18:00:00Z")
                 ::lease/data {:foo 123}}
        lease-b-id "foo/bar/456"
        lease-b {::lease/id lease-b-id
                 ::lease/key [:foo 456]
                 ::lease/expires-at (Instant/parse "2022-09-21T18:00:00Z")
                 ::lease/data {:foo 456}}]
    (swap! store assoc lease-a-id lease-a)
    (swap! store assoc lease-b-id lease-b)
    (is (nil? (lease/invalidate! store [:xyz true])))
    (is (= #{lease-a-id lease-b-id} (set (keys @store))))
    (is (nil? (lease/invalidate! store [:foo 456])))
    (is (= [lease-a-id] (keys @store)))))


(deftest maintenance-helpers
  ,,,)


(deftest maintenance-logic
  ,,,)
