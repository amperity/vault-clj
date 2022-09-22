(ns vault.util-test
  (:require
    [clojure.test :refer [deftest testing is]]
    [vault.util :as u])
  (:import
    java.time.Instant))


(deftest misc-utils
  (testing "update-some"
    (is (= {:foo true} (u/update-some {:foo true} :bar inc)))
    (is (= {:foo true, :bar 124}
           (u/update-some {:foo true, :bar 123} :bar inc)))))


(deftest kebab-casing
  (testing "kebab-keyword"
    (is (= :foo-bar (u/kebab-keyword :foo-bar)))
    (is (= :a-b (u/kebab-keyword :a_b)))
    (is (= :x-y-z (u/kebab-keyword "x_y_z"))))
  (testing "kebabify-keys"
    (is (= {:one-two [{:x 123
                       :y-z true}]
            :three "456"}
           (u/kebabify-keys
             {"one_two" [{"x" 123
                          "y_z" true}]
              "three" "456"}))))
  (testing "kebabify-body-data"
    (is (= {:foo-bar 123
            :baz true}
           (u/kebabify-body-data
             {"abc" "def"
              "data" {"foo_bar" 123
                      "baz" true}
              "xyz" 890})))))


(deftest snake-casing
  (testing "snake-str"
    (is (= "foo_bar" (u/snake-str "foo_bar")))
    (is (= "a_b" (u/snake-str :a_b)))
    (is (= "x_y_z" (u/snake-str :x-y-z))))
  (testing "snakify-keys"
    (is (= {"one_two" [{"x" 123
                        "y_z" true}]
            "three" "456"}
           (u/snakify-keys
             {:one-two [{:x 123
                         :y-z true}]
              :three "456"})))))


(deftest string-casing
  (testing "stringify-key"
    (is (= "abc" (u/stringify-key "abc")))
    (is (= "123" (u/stringify-key 123)))
    (is (= "foo" (u/stringify-key :foo)))
    (is (= "foo-bar" (u/stringify-key :foo-bar)))
    (is (= "foo_bar" (u/stringify-key :foo_bar)))
    (is (= "foo.bar/baz" (u/stringify-key :foo.bar/baz))))
  (testing "stringify-keys"
    (is (= {"foo" [{"x" 123, "y/z" true}]
            "bar" "456"}
           (u/stringify-keys
             {:foo [{:x 123, :y/z true}]
              :bar "456"})))))


(deftest encoding
  (testing "hex"
    (is (= "a0" (u/hex-encode (byte-array [160]))))
    (is (= "0123456789abcdef" (u/hex-encode (byte-array [0x01 0x23 0x45 0x67 0x89 0xab 0xcd 0xef])))))
  (testing "base64"
    (let [good-news "Good news, everyone!"
          data (.getBytes good-news)]
      (is (= "R29vZCBuZXdzLCBldmVyeW9uZSE=" (u/base64-encode data)))
      (is (= "R29vZCBuZXdzLCBldmVyeW9uZSE=" (u/base64-encode good-news)))
      (is (= good-news (String. (u/base64-decode "R29vZCBuZXdzLCBldmVyeW9uZSE="))))))
  (testing "sha-256"
    (is (= "dbd318c1c462aee872f41109a4dfd3048871a03dedd0fe0e757ced57dad6f2d7"
           (u/sha-256 "foo bar baz")))
    (is (= "64989ccbf3efa9c84e2afe7cee9bc5828bf0fcb91e44f8c1e591638a2c2e90e3"
           (u/sha-256 "alpha beta gamma")))))


(deftest paths
  (testing "trim-path"
    (is (= "foo" (u/trim-path "foo")))
    (is (= "foo" (u/trim-path "/foo")))
    (is (= "foo" (u/trim-path "foo/")))
    (is (= "foo" (u/trim-path "/foo/")))
    (is (= "foo/bar/baz" (u/trim-path "/foo/bar/baz/"))))
  (testing "join-path"
    (is (= "foo/bar" (u/join-path "foo" "bar")))
    (is (= "foo/bar/baz/qux" (u/join-path "foo/bar/" "/baz" "qux/")))))


(deftest time-controls
  (let [t (Instant/parse "2021-08-31T22:06:17Z")]
    (u/with-now t
      (is (= t (u/now)))
      (is (= 1630447577000 (u/now-milli))))))
