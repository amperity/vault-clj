(ns vault.client.response-test
  (:require
    [clojure.test :refer [deftest testing is]]
    [vault.client.response
     :as resp
     :refer [sync-handler promise-handler completable-future-handler]])
  (:import
    clojure.lang.IPending
    java.util.concurrent.CompletableFuture))


(deftest sync-response
  (let [handler sync-handler]
    (testing "success case"
      (let [response (resp/create handler nil)]
        (is (instance? IPending response))
        (is (not (realized? response)))
        (is (any? (resp/on-success! handler response :ok)))
        (is (realized? response))
        (let [ret (resp/return handler response)]
          (is (= :ok ret))
          (is (= :ok (resp/await handler ret 1 :not-yet)))
          (is (= :ok (resp/await handler ret))))))
    (testing "error case"
      (let [response (resp/create handler nil)]
        (is (instance? IPending response))
        (is (not (realized? response)))
        (is (any? (resp/on-error! handler response (RuntimeException. "BOOM"))))
        (is (realized? response))
        (is (thrown-with-msg? RuntimeException #"BOOM"
              (resp/return handler response)))))))


(deftest promise-response
  (let [handler promise-handler]
    (testing "success case"
      (let [response (resp/create handler nil)]
        (is (instance? IPending response))
        (is (not (realized? response)))
        (let [ret (resp/return handler response)]
          (is (instance? IPending ret))
          (is (not (realized? ret)))
          (is (= :not-yet (resp/await handler ret 1 :not-yet)))
          (is (any? (resp/on-success! handler response :ok)))
          (is (realized? ret))
          (is (= :ok @ret))
          (is (= :ok (resp/await handler ret 1 :not-yet)))
          (is (= :ok (resp/await handler ret))))))
    (testing "error case"
      (let [response (resp/create handler nil)]
        (is (instance? IPending response))
        (is (not (realized? response)))
        (let [ret (resp/return handler response)]
          (is (instance? IPending response))
          (is (not (realized? ret)))
          (is (= :not-yet (resp/await handler ret 1 :not-yet)))
          (is (any? (resp/on-error! handler response (RuntimeException. "BOOM"))))
          (is (realized? ret))
          (is (instance? RuntimeException @ret))
          (is (= "BOOM" (ex-message @ret)))
          (is (thrown-with-msg? RuntimeException #"BOOM"
                (resp/await handler ret 1 :not-yet)))
          (is (thrown-with-msg? RuntimeException #"BOOM"
                (resp/await handler ret))))))))


(deftest completable-future-response
  (let [handler completable-future-handler]
    (testing "success case"
      (let [response (resp/create handler nil)]
        (is (instance? CompletableFuture response))
        (is (not (.isDone response)))
        (let [ret (resp/return handler response)]
          (is (instance? CompletableFuture ret))
          (is (not (.isDone ret)))
          (is (= :not-yet (resp/await handler ret 1 :not-yet)))
          (is (any? (resp/on-success! handler response :ok)))
          (is (.isDone ret))
          (is (= :ok @ret))
          (is (= :ok (resp/await handler ret 1 :not-yet)))
          (is (= :ok (resp/await handler ret))))))
    (testing "error case"
      (let [response (resp/create handler nil)]
        (is (instance? CompletableFuture response))
        (is (not (.isDone response)))
        (let [ret (resp/return handler response)]
          (is (instance? CompletableFuture ret))
          (is (not (.isDone ret)))
          (is (= :not-yet (resp/await handler ret 1 :not-yet)))
          (is (any? (resp/on-error! handler response (RuntimeException. "BOOM"))))
          (is (.isDone ret))
          (is (thrown-with-msg? Exception #"BOOM"
                @ret))
          (is (thrown-with-msg? Exception #"BOOM"
                (resp/await handler ret 1 :not-yet)))
          (is (thrown-with-msg? Exception #"BOOM"
                (resp/await handler ret))))))))
