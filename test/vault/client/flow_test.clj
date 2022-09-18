(ns vault.client.flow-test
  (:require
    [clojure.test :refer [deftest testing is]]
    [vault.client.flow :as f])
  (:import
    clojure.lang.IPending
    java.util.concurrent.CompletableFuture))


(defn nop
  "A no-op request function."
  [_]
  nil)


(deftest sync-response
  (let [handler f/sync-handler]
    (testing "success case"
      (let [result (f/call
                     handler nil
                     (fn request
                       [state]
                       (is (instance? IPending state))
                       (is (not (realized? state)))
                       (is (any? (f/on-success! handler state :ok)))
                       (is (realized? state))))]
        (is (= :ok result))
        (is (= :ok (f/await handler result 1 :not-yet)))
        (is (= :ok (f/await handler result)))))
    (testing "error case"
      (is (thrown-with-msg? RuntimeException #"BOOM"
            (f/call
              handler nil
              (fn request
                [state]
                (is (instance? IPending state))
                (is (not (realized? state)))
                (is (any? (f/on-error! handler state (RuntimeException. "BOOM"))))
                (is (realized? state)))))))))


(deftest promise-response
  (let [handler f/promise-handler]
    (testing "success case"
      (let [state-ref (volatile! nil)
            result (f/call
                     handler nil
                     (fn request
                       [state]
                       (vreset! state-ref state)
                       (is (instance? IPending state))
                       (is (not (realized? state)))))]
        (is (instance? IPending result))
        (is (not (realized? result)))
        (is (= :not-yet (f/await handler result 1 :not-yet)))
        (is (any? (f/on-success! handler @state-ref :ok)))
        (is (realized? result))
        (is (= :ok @result))
        (is (= :ok (f/await handler result 1 :not-yet)))
        (is (= :ok (f/await handler result)))))
    (testing "error case"
      (let [state-ref (volatile! nil)
            result (f/call
                     handler nil
                     (fn request
                       [state]
                       (vreset! state-ref state)
                       (is (instance? IPending state))
                       (is (not (realized? state)))))]
        (is (instance? IPending result))
        (is (not (realized? result)))
        (is (= :not-yet (f/await handler result 1 :not-yet)))
        (is (any? (f/on-error! handler @state-ref (RuntimeException. "BOOM"))))
        (is (realized? result))
        (is (instance? RuntimeException @result))
        (is (= "BOOM" (ex-message @result)))
        (is (thrown-with-msg? RuntimeException #"BOOM"
              (f/await handler result 1 :not-yet)))
        (is (thrown-with-msg? RuntimeException #"BOOM"
              (f/await handler result)))))))


(deftest completable-future-response
  (let [handler f/completable-future-handler]
    (testing "success case"
      (let [state-ref (volatile! nil)
            result (f/call
                     handler nil
                     (fn request
                       [state]
                       (vreset! state-ref state)
                       (is (instance? CompletableFuture state))
                       (is (not (.isDone state)))))]
        (is (instance? CompletableFuture result))
        (is (not (.isDone result)))
        (is (= :not-yet (f/await handler result 1 :not-yet)))
        (is (any? (f/on-success! handler @state-ref :ok)))
        (is (.isDone result))
        (is (= :ok @result))
        (is (= :ok (f/await handler result 1 :not-yet)))
        (is (= :ok (f/await handler result)))))
    (testing "error case"
      (let [state-ref (volatile! nil)
            result (f/call
                     handler nil
                     (fn request
                       [state]
                       (vreset! state-ref state)
                       (is (instance? CompletableFuture state))
                       (is (not (.isDone state)))))]
        (is (instance? CompletableFuture result))
        (is (not (.isDone result)))
        (is (= :not-yet (f/await handler result 1 :not-yet)))
        (is (any? (f/on-error! handler @state-ref (RuntimeException. "BOOM"))))
        (is (.isDone result))
        (is (thrown-with-msg? Exception #"BOOM"
              @result))
        (is (thrown-with-msg? Exception #"BOOM"
              (f/await handler result 1 :not-yet)))
        (is (thrown-with-msg? Exception #"BOOM"
              (f/await handler result)))))))
