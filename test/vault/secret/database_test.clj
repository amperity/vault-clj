(ns vault.secret.database-test
  (:require
    [clojure.test :refer [is testing deftest]]
    [vault.client.http :as http]
    [vault.integration :refer [with-dev-server cli]]
    [vault.secret.database :as database]))


(deftest with-mount
  (testing "different mounts"
    (let [client (http/http-client "https://foo.com")]
      (is (nil? (::database/mount client)))
      (is (= "test-mount" (::database/mount (database/with-mount client "test-mount")))))))


(deftest ^:service-required http-api
  (let [db-host (or (System/getenv "VAULT_POSTGRES_HOST") "localhost:5432")
        db-name (or (System/getenv "VAULT_POSTGRES_DATABASE") "postgres")
        admin-user (or (System/getenv "VAULT_POSTGRES_ADMIN_USER") "postgres")
        admin-pass (System/getenv "VAULT_POSTGRES_ADMIN_PASS")
        grant-role (or (System/getenv "VAULT_POSTGRES_ROLE") "postgres")]
    (when admin-pass
      (with-dev-server
        (cli "secrets" "enable" "database")
        (cli "write" "database/config/test-db"
             "plugin_name=postgresql-database-plugin"
             (format "connection_url=postgresql://{{username}}:{{password}}@%s/%s?sslmode=disable"
                     db-host
                     db-name)
             (str "username=" admin-user)
             (str "password=" admin-pass)
             "allowed_roles=*")
        (cli "write" "database/roles/postgres-role"
             "db_name=test-db"
             (str "creation_statements=CREATE ROLE \"{{name}}\" WITH LOGIN PASSWORD '{{password}}' VALID UNTIL '{{expiration}}'; GRANT "
                  grant-role " TO \"{{name}}\";")
             "revocation_statements=DROP ROLE IF EXISTS \"{{name}}\";")
        (testing "generate nonexistent role"
          (is (thrown? Exception
                (database/generate-credentials! client "foo"))))
        (testing "generate valid credentials"
          (let [creds (database/generate-credentials! client "postgres-role")]
            (is (string? (:username creds)))
            (is (string? (:password creds)))
            ;; TODO: test credentials somehow
            ,,,))))))
