(ns vault.kvv2-test
  (:require
    [clojure.test :refer [testing deftest is]])
  (:import
    (clojure.lang
      ExceptionInfo)))


(deftest read
  (let [lookup-response-valid-path {:data {:data     {:foo "bar"}
                                           :metadata {:created_time  "2018-03-22T02:24:06.945319214Z"
                                                      :deletion_time ""
                                                      :destroyed     false
                                                      :version       1}}}
        path-passed-in "path/passed/in"
        token-passed-in "fake-token"
        vault-url "https://vault.example.amperity.com"
        client (vault.core/new-client vault-url)]
    (testing "Read responds correctly if secret is successfully located"
      (with-redefs
        [clj-http.client/get
         (fn [url opts]
           (is (= (str vault-url "/v1/data/" path-passed-in) url))
           (is (= token-passed-in (get (:headers opts) "X-Vault-Token")))
           lookup-response-valid-path)]
        (is (= {:foo "bar"} (vault-kv/read client vault-url)))))
    (testing "Read responds correctly if no secret is found"
      (with-redefs
        [clj-http.client/get
         (fn [url opts]
           (is (= (str vault-url "/v1/data/" path-passed-in) url))
           (is (= token-passed-in (get (:headers opts) "X-Vault-Token")))
           {:errors []})]
        (try
          (vault-kv/read client vault-url)
          (is false)
          (catch ExceptionInfo e
            (is (= {:status 404} (ex-data e)))))))))
