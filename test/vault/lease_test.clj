(ns vault.lease-test
  (:require
    [clojure.test :refer [deftest testing is]]
    [vault.lease :as lease]
    [vault.util :as u])
  (:import
    java.time.Instant))


(deftest spec-validation
  (is (not (lease/valid? nil)))
  (is (not (lease/valid? "foo")))
  (is (lease/valid? {}))
  (is (lease/valid? #::lease
                    {:id "secret/foo/bar/123-456-789"
                     :duration 600
                     :renewable? false}))
  (is (lease/valid? {::foo 123})))


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
  (testing "renewable-lease"
    (testing "with non-renewable lease"
      (is (= {::lease/id "foo/bar"
              ::lease/renewable? false}
             (lease/renewable-lease
               {::lease/id "foo/bar"
                ::lease/renewable? false}
               {:renew? true}))
          "should return lease unchanged"))
    (testing "without opt-in"
      (is (= {::lease/id "foo/bar"
              ::lease/renewable? true}
             (lease/renewable-lease
               {::lease/id "foo/bar"
                ::lease/renewable? true}
               {}))
          "should return lease unchanged"))
    (testing "with opt-in"
      (is (= {::lease/id "foo/bar"
              ::lease/renewable? true
              ::lease/renew-within 60}
             (lease/renewable-lease
               {::lease/id "foo/bar"
                ::lease/renewable? true}
               {:renew? true}))
          "should return lease with renew-within setting"))
    (testing "with full opts"
      (is (= {::lease/id "foo/bar"
              ::lease/renewable? true
              ::lease/renew-within 300
              ::lease/renew-increment 3600
              ::lease/on-renew prn
              ::lease/on-error prn}
             (lease/renewable-lease
               {::lease/id "foo/bar"
                ::lease/renewable? true}
               {:renew? true
                :renew-within 300
                :renew-increment 3600
                :on-renew prn
                :on-error prn}))
          "should return lease with optional settings")))
  (testing "rotatable-lease"
    (let [rotate-fn (constantly true)]
      (testing "without rotation fn"
        (is (thrown? IllegalArgumentException
              (lease/rotatable-lease
                {::lease/id "foo/bar"}
                {:rotate? true}
                nil))
            "should throw an exception"))
      (testing "without opt-in"
        (is (= {::lease/id "foo/bar"}
               (lease/rotatable-lease
                 {::lease/id "foo/bar"}
                 {}
                 rotate-fn))
            "should return lease unchanged"))
      (testing "with opt-in"
        (is (= {::lease/id "foo/bar"
                ::lease/rotate-fn rotate-fn
                ::lease/rotate-within 60}
               (lease/rotatable-lease
                 {::lease/id "foo/bar"}
                 {:rotate? true}
                 rotate-fn))
            "should return lease with rotation settings"))
      (testing "with full opts"
        (is (= {::lease/id "foo/bar"
                ::lease/rotate-fn rotate-fn
                ::lease/rotate-within 300
                ::lease/on-rotate prn
                ::lease/on-error prn}
               (lease/rotatable-lease
                 {::lease/id "foo/bar"}
                 {:rotate? true
                  :rotate-within 300
                  :on-rotate prn
                  :on-error prn}
                 rotate-fn))
            "should return lease with optional settings")))))


(deftest maintenance-logic
  (testing "on active lease"
    (let [store (lease/new-store)
          lease-id "foo/bar/123"
          lease {::lease/id lease-id
                 ::lease/renewable? true
                 ::lease/renew-within 60
                 ::lease/rotate-within 60
                 ::lease/rotate-fn (constantly nil)
                 ::lease/expires-at (Instant/parse "2022-09-22T09:00:00Z")
                 ::lease/data {:foo 123}}
          renew-calls (atom 0)
          renew-fn (fn [_] (swap! renew-calls inc))]
      (swap! store assoc lease-id lease)
      (u/with-now (Instant/parse "2022-09-22T03:00:00Z")
        (is (nil? (lease/maintain! {:leases store} renew-fn))))
      (is (zero? @renew-calls)
          "should not call renew-fn")
      (is (= lease (first (vals @store)))
          "should leave lease unchanged in store")))
  (testing "on unrenewable lease"
    (let [store (lease/new-store)
          lease-id "foo/bar/123"
          lease {::lease/id lease-id
                 ::lease/renewable? false
                 ::lease/expires-at (Instant/parse "2022-09-22T09:00:00Z")
                 ::lease/data {:foo 123}}
          renew-calls (atom 0)
          renew-fn (fn [_] (swap! renew-calls inc))]
      (swap! store assoc lease-id lease)
      (u/with-now (Instant/parse "2022-09-22T08:59:50Z")
        (is (nil? (lease/maintain! {:leases store} renew-fn))))
      (is (zero? @renew-calls)
          "should not call renew-fn")
      (is (= lease (first (vals @store)))
          "should leave lease unchanged in store")))
  (testing "on lease in renewal backoff"
    (let [store (lease/new-store)
          lease-id "foo/bar/123"
          lease {::lease/id lease-id
                 ::lease/renewable? true
                 ::lease/renew-within 300
                 ::lease/renew-after (Instant/parse "2022-09-22T09:28:00Z")
                 ::lease/expires-at (Instant/parse "2022-09-22T09:30:00Z")
                 ::lease/data {:foo 123}}
          renew-calls (atom 0)
          renew-fn (fn [_] (swap! renew-calls inc))]
      (swap! store assoc lease-id lease)
      (u/with-now (Instant/parse "2022-09-22T09:27:00Z")
        (is (nil? (lease/maintain! {:leases store} renew-fn))))
      (is (zero? @renew-calls)
          "should not call renew-fn")
      (is (= lease (first (vals @store)))
          "should leave lease unchanged in store")))
  (testing "on renewable lease"
    (testing "with successful renewal"
      (let [store (lease/new-store)
            callbacks (atom #{})
            make-cb (fn [tag] (fn [_] (swap! callbacks conj tag)))
            lease-id "foo/bar/123"
            lease {::lease/id lease-id
                   ::lease/renewable? true
                   ::lease/renew-within 60
                   ::lease/expires-at (Instant/parse "2022-09-22T09:30:00Z")
                   ::lease/on-renew (make-cb :renew)
                   ::lease/on-rotate (make-cb :rotate)
                   ::lease/on-error (make-cb :err)
                   ::lease/data {:foo 123}}
            renew-fn (fn [_]
                       (lease/update!
                         store
                         {::lease/id lease-id
                          ::lease/expires-at (Instant/parse "2022-09-22T10:30:00Z")}))]
        (swap! store assoc lease-id lease)
        (u/with-now (Instant/parse "2022-09-22T09:29:10Z")
          (is (nil? (lease/maintain! {:leases store} renew-fn))))
        (let [lease' (first (vals @store))]
          (is (= (Instant/parse "2022-09-22T10:30:00Z")
                 (::lease/expires-at lease'))
              "should update lease expiry")
          (is (= (Instant/parse "2022-09-22T09:30:10Z")
                 (::lease/renew-after lease'))
              "should set lease renewal backoff"))
        (is (= #{:renew} @callbacks)
            "should invoke on-renew callbacks")))
    (testing "with failed renewal"
      (let [store (lease/new-store)
            callbacks (atom #{})
            make-cb (fn [tag] (fn [_] (swap! callbacks conj tag)))
            lease-id "foo/bar/123"
            lease {::lease/id lease-id
                   ::lease/renewable? true
                   ::lease/renew-within 60
                   ::lease/expires-at (Instant/parse "2022-09-22T09:30:00Z")
                   ::lease/on-renew (make-cb :renew)
                   ::lease/on-rotate (make-cb :rotate)
                   ::lease/on-error (make-cb :err)
                   ::lease/data {:foo 123}}
            renew-fn (fn [_] (throw (RuntimeException. "BOOM")))]
        (swap! store assoc lease-id lease)
        (u/with-now (Instant/parse "2022-09-22T09:29:10Z")
          (is (nil? (lease/maintain! {:leases store} renew-fn))))
        (is (= lease (first (vals @store)))
            "should leave lease unchanged in store")
        (is (= #{:err} @callbacks)
            "should invoke on-error callbacks"))))
  (testing "on rotatable lease"
    (testing "with successful rotation"
      (let [store (lease/new-store)
            callbacks (atom #{})
            make-cb (fn [tag] (fn [_] (swap! callbacks conj tag)))
            lease-id "foo/bar/123"
            lease {::lease/id lease-id
                   ::lease/rotate-within 60
                   ::lease/rotate-fn (constantly true)
                   ::lease/expires-at (Instant/parse "2022-09-22T09:30:00Z")
                   ::lease/on-renew (make-cb :renew)
                   ::lease/on-rotate (make-cb :rotate)
                   ::lease/on-error (make-cb :err)
                   ::lease/data {:foo 123}}
            renew-calls (atom 0)
            renew-fn (fn [_] (swap! renew-calls inc))]
        (swap! store assoc lease-id lease)
        (u/with-now (Instant/parse "2022-09-22T09:29:10Z")
          (is (nil? (lease/maintain! {:leases store} renew-fn))))
        (is (zero? @renew-calls)
            "should not call renew-fn")
        (is (empty? @store)
            "should remove old lease from store")
        (is (= #{:rotate} @callbacks)
            "should invoke on-rotate callbacks")))
    (testing "with failed rotation"
      (let [store (lease/new-store)
            callbacks (atom #{})
            make-cb (fn [tag] (fn [_] (swap! callbacks conj tag)))
            lease-id "foo/bar/123"
            lease {::lease/id lease-id
                   ::lease/rotate-within 60
                   ::lease/rotate-fn (fn [] (throw (RuntimeException. "BOOM")))
                   ::lease/expires-at (Instant/parse "2022-09-22T09:30:00Z")
                   ::lease/on-renew (make-cb :renew)
                   ::lease/on-rotate (make-cb :rotate)
                   ::lease/on-error (make-cb :err)
                   ::lease/data {:foo 123}}
            renew-calls (atom 0)
            renew-fn (fn [_] (swap! renew-calls inc))]
        (swap! store assoc lease-id lease)
        (u/with-now (Instant/parse "2022-09-22T09:29:10Z")
          (is (nil? (lease/maintain! {:leases store} renew-fn))))
        (is (zero? @renew-calls)
            "should not call renew-fn")
        (is (= lease (first (vals @store)))
            "should leave lease unchanged in store")
        (is (= #{:err} @callbacks)
            "should invoke on-error callbacks"))))
  (testing "on expired lease"
    (let [store (lease/new-store)
          lease-id "foo/bar/123"
          lease {::lease/id lease-id
                 ::lease/renewable? true
                 ::lease/renew-within 60
                 ::lease/expires-at (Instant/parse "2022-09-22T09:00:00Z")
                 ::lease/data {:foo 123}}
          renew-calls (atom 0)
          renew-fn (fn [_] (swap! renew-calls inc))]
      (swap! store assoc lease-id lease)
      (u/with-now (Instant/parse "2022-09-22T12:00:00Z")
        (is (nil? (lease/maintain! {:leases store} renew-fn))))
      (is (zero? @renew-calls)
          "should not call renew-fn")
      (is (empty? @store)
          "should remove it from store")))
  (testing "with unexpected error"
    (let [store (lease/new-store)
          lease-id "foo/bar/123"
          lease {::lease/id lease-id
                 ::lease/renewable? true
                 ::lease/renew-within 60
                 ::lease/expires-at (Instant/parse "2022-09-22T09:00:00Z")
                 ::lease/data {:foo 123}}
          renew-calls (atom 0)
          renew-fn (fn [_] (swap! renew-calls inc))]
      (swap! store assoc lease-id lease)
      (with-redefs [lease/renew? (fn [_] (throw (RuntimeException. "BOOM")))]
        (is (nil? (lease/maintain! {:leases store} renew-fn))))
      (is (zero? @renew-calls)
          "should not call renew-fn")
      (is (= lease (first (vals @store)))
          "should leave lease in store"))))
