(ns vault.client.response-test
  (:require
    [clojure.test :refer [deftest testing is]]
    [vault.client.response :as resp :refer [sync-handler promise-handler]])
  (:import
    clojure.lang.IPending))


(deftest sync-response
  (testing "success case"
    (let [response (resp/create sync-handler nil)]
      (is (instance? IPending response))
      (is (not (realized? response)))
      (is (any? (resp/on-success! sync-handler response :ok)))
      (is (realized? response))
      (is (= :ok (resp/return sync-handler response)))))
  (testing "error case"
    (let [response (resp/create sync-handler nil)]
      (is (instance? IPending response))
      (is (not (realized? response)))
      (is (any? (resp/on-error! sync-handler response (RuntimeException. "BOOM"))))
      (is (realized? response))
      (is (thrown-with-msg? RuntimeException #"BOOM"
            (resp/return sync-handler response))))))


(deftest promise-response
  (testing "success case"
    (let [response (resp/create promise-handler nil)]
      (is (instance? IPending response))
      (is (not (realized? response)))
      (let [ret (resp/return promise-handler response)]
        (is (instance? IPending ret))
        (is (not (realized? ret)))
        (is (any? (resp/on-success! promise-handler response :ok)))
        (is (realized? ret))
        (is (= :ok @ret)))))
  (testing "error case"
    (let [response (resp/create promise-handler nil)]
      (is (instance? IPending response))
      (is (not (realized? response)))
      (let [ret (resp/return promise-handler response)]
        (is (instance? IPending response))
        (is (not (realized? ret)))
        (is (any? (resp/on-error! promise-handler response (RuntimeException. "BOOM"))))
        (is (realized? ret))
        (is (instance? RuntimeException @ret))
        (is (= "BOOM" (ex-message @ret)))))))
