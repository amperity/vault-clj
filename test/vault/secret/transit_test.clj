(ns vault.secret.transit-test
  (:require
    [clojure.test :refer [deftest testing is]]
    [vault.integration :refer [with-dev-server cli]]
    [vault.secret.transit :as transit]))


(deftest ^:integration http-api
  (with-dev-server
    (cli "secrets" "enable" "transit")
    (testing "on missing keys"
      (testing "read-key"
        (is (thrown-with-msg? Exception #"not found"
              (transit/read-key client "missing"))
            "should throw not-found error"))
      (testing "rotate-key!"
        (is (thrown-with-msg? Exception #"not found"
              (transit/rotate-key! client "missing"))
            "should throw not-found error"))
      (testing "update-key-configuration!"
        (is (thrown-with-msg? Exception #"no existing key .+ found"
              (transit/update-key-configuration!
                client "missing"
                {:min-encryption-version 2}))
            "should throw not-found error"))
      ;; NOTE: not covering encrypt-data, since it automatically creates a key
      (testing "decrypt-data!"
        (is (thrown-with-msg? Exception #"encryption key not found"
              (transit/decrypt-data!
                client "missing"
                "vault:v1:SSBhbSBMcnJyLCBydWxlciBvZiB0aGUgcGxhbmV0IE9taWNyb24gUGVyc2VpIDgh"))
            "should throw not-found error")))
    (cli "write" "-force" "transit/keys/test")
    (testing "read-key"
      (let [key-info (transit/read-key client "test")]
        (is (= "test" (:name key-info)))
        (is (= "aes256-gcm96" (:type key-info)))
        (is (true? (:supports-encryption key-info)))
        (is (true? (:supports-decryption key-info)))
        (is (false? (:supports-signing key-info)))
        (is (= 0 (:min-available-version key-info)))
        (is (= 0 (:min-encryption-version key-info)))
        (is (= 1 (:min-decryption-version key-info)))
        (is (= 1 (:latest-version key-info)))
        (is (map? (:keys key-info)))
        (is (= 1 (count (:keys key-info))))
        (let [[version created-at] (first (:keys key-info))]
          (is (= 1 version))
          (is (inst? created-at)))))
    (testing "single mode"
      (let [plaintext "I am Lrrr, ruler of the planet Omicron Persei 8!"
            ciphertext (atom nil)]
        (testing "encrypt-data!"
          (let [result (transit/encrypt-data! client "test" plaintext)]
            (is (string? (:ciphertext result)))
            (is (= 1 (:key-version result)))
            (reset! ciphertext (:ciphertext result))))
        (testing "decrypt-data!"
          (testing "to bytes"
            (let [result (transit/decrypt-data! client "test" @ciphertext)]
              (is (bytes? (:plaintext result)))
              (is (= plaintext (String. ^bytes (:plaintext result) "UTF-8")))))
          (testing "to string"
            (let [result (transit/decrypt-data! client "test" @ciphertext {:as-string true})]
              (is (string? (:plaintext result)))
              (is (= plaintext (:plaintext result))))))))
    (testing "batch mode"
      (let [inputs [{:plaintext "Good news, everyone!"
                     :reference "Professor"}
                    {:plaintext "Bite my shiny metal ass"
                     :reference "Bender"}
                    {:plaintext "Oh lord"
                     :reference "Leela"}]
            batch (atom nil)]
        (testing "encrypt-data!"
          (let [result (transit/encrypt-data! client "test" inputs)]
            (is (vector? result))
            (is (= 3 (count result)))
            (is (= ["Professor" "Bender" "Leela"] (map :reference result)))
            (is (every? :ciphertext result))
            (is (every? #(= 1 (:key-version %)) result))
            (reset! batch result)))
        (testing "decrypt-data!"
          (testing "to bytes"
            (let [result (transit/decrypt-data! client "test" @batch)]
              (is (vector? result))
              (is (= 3 (count result)))
              (is (= ["Professor" "Bender" "Leela"] (map :reference result)))
              (is (every? (comp bytes? :plaintext) result))
              (is (= "Good news, everyone!" (String. (:plaintext (first result)) "UTF-8")))))
          (testing "to string"
            (let [result (transit/decrypt-data! client "test" @batch {:as-string true})]
              (is (vector? result))
              (is (= inputs result)))))))
    (testing "rotation"
      (testing "rotate-key!"
        (let [key-info (transit/rotate-key! client "test")]
          (is (= "test" (:name key-info)))
          (is (= "aes256-gcm96" (:type key-info)))
          (is (= 0 (:min-available-version key-info)))
          (is (= 0 (:min-encryption-version key-info)))
          (is (= 1 (:min-decryption-version key-info)))
          (is (= 2 (:latest-version key-info)))
          (is (map? (:keys key-info)))
          (is (= #{1 2} (set (keys (:keys key-info)))))
          (is (inst? (get-in key-info [:keys 2])))))
      (testing "update-key-configuration!"
        (let [key-info (transit/update-key-configuration!
                         client "test"
                         {:min-encryption-version 2})]
          (is (= "test" (:name key-info)))
          (is (= 0 (:min-available-version key-info)))
          (is (= 2 (:min-encryption-version key-info)))
          (is (thrown-with-msg? Exception #"requested version for encryption is less than the minimum encryption key version"
            (transit/encrypt-data! client "test" "gimme the old one" {:key-version 1}))))))))
