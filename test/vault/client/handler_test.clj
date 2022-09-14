(ns vault.client.handler-test
  (:require
    [clojure.test :refer [deftest testing is]]
    [vault.client.handler :as h])
  (:import
    clojure.lang.IPending
    java.util.concurrent.CompletableFuture))


(defn nop
  "A no-op request function."
  [_]
  nil)


(deftest sync-response
  (let [handler h/sync-handler]
    (testing "success case"
      (let [result (h/call
                     handler nil
                     (fn request
                       [state]
                       (is (instance? IPending state))
                       (is (not (realized? state)))
                       (is (any? (h/on-success! handler state :ok)))
                       (is (realized? state))))]
        (is (= :ok result))
        (is (= :ok (h/await handler result 1 :not-yet)))
        (is (= :ok (h/await handler result)))))
    (testing "error case"
      (is (thrown-with-msg? RuntimeException #"BOOM"
            (h/call
              handler nil
              (fn request
                [state]
                (is (instance? IPending state))
                (is (not (realized? state)))
                (is (any? (h/on-error! handler state (RuntimeException. "BOOM"))))
                (is (realized? state)))))))))


(deftest promise-response
  (let [handler h/promise-handler]
    (testing "success case"
      (let [state-ref (volatile! nil)
            result (h/call
                     handler nil
                     (fn request
                       [state]
                       (vreset! state-ref state)
                       (is (instance? IPending state))
                       (is (not (realized? state)))))]
        (is (instance? IPending result))
        (is (not (realized? result)))
        (is (= :not-yet (h/await handler result 1 :not-yet)))
        (is (any? (h/on-success! handler @state-ref :ok)))
        (is (realized? result))
        (is (= :ok @result))
        (is (= :ok (h/await handler result 1 :not-yet)))
        (is (= :ok (h/await handler result)))))
    (testing "error case"
      (let [state-ref (volatile! nil)
            result (h/call
                     handler nil
                     (fn request
                       [state]
                       (vreset! state-ref state)
                       (is (instance? IPending state))
                       (is (not (realized? state)))))]
        (is (instance? IPending result))
        (is (not (realized? result)))
        (is (= :not-yet (h/await handler result 1 :not-yet)))
        (is (any? (h/on-error! handler @state-ref (RuntimeException. "BOOM"))))
        (is (realized? result))
        (is (instance? RuntimeException @result))
        (is (= "BOOM" (ex-message @result)))
        (is (thrown-with-msg? RuntimeException #"BOOM"
              (h/await handler result 1 :not-yet)))
        (is (thrown-with-msg? RuntimeException #"BOOM"
              (h/await handler result)))))))


(deftest completable-future-response
  (let [handler h/completable-future-handler]
    (testing "success case"
      (let [state-ref (volatile! nil)
            result (h/call
                     handler nil
                     (fn request
                       [state]
                       (vreset! state-ref state)
                       (is (instance? CompletableFuture state))
                       (is (not (.isDone state)))))]
        (is (instance? CompletableFuture result))
        (is (not (.isDone result)))
        (is (= :not-yet (h/await handler result 1 :not-yet)))
        (is (any? (h/on-success! handler @state-ref :ok)))
        (is (.isDone result))
        (is (= :ok @result))
        (is (= :ok (h/await handler result 1 :not-yet)))
        (is (= :ok (h/await handler result)))))
    (testing "error case"
      (let [state-ref (volatile! nil)
            result (h/call
                     handler nil
                     (fn request
                       [state]
                       (vreset! state-ref state)
                       (is (instance? CompletableFuture state))
                       (is (not (.isDone state)))))]
        (is (instance? CompletableFuture result))
        (is (not (.isDone result)))
        (is (= :not-yet (h/await handler result 1 :not-yet)))
        (is (any? (h/on-error! handler @state-ref (RuntimeException. "BOOM"))))
        (is (.isDone result))
        (is (thrown-with-msg? Exception #"BOOM"
              @result))
        (is (thrown-with-msg? Exception #"BOOM"
              (h/await handler result 1 :not-yet)))
        (is (thrown-with-msg? Exception #"BOOM"
              (h/await handler result)))))))
