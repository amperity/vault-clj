(ns vault.secrets.kvv2
  (:require
    [vault.api-util :as api-util]
    [vault.core :as vault])
  (:import
    (clojure.lang
      ExceptionInfo)))


(defn read-secret
  ([client mount path opts]
   (try
     (:data (vault/read-secret client (str mount "/data/" path) (dissoc opts :not-found)))

     (catch ExceptionInfo ex
       (if (and (contains? opts :not-found)
                (= ::api-util/api-error (:type (ex-data ex)))
                (= 404 (:status (ex-data ex))))
         (:not-found opts)
         (throw ex)))))
  ([client mount path]
   (read-secret client mount path nil)))


(defn write-secret!
  [client mount path data]
  (let [result (vault/write-secret! client (str mount "/data/" path) {:data data})]
    (or (:data result) result)))


(defn write-config!
  [client mount data]
  (vault/write-secret! client (str mount "/config") data))


(defn read-config
  ([client mount opts]
   (vault/read-secret client (str mount "/config") opts))
  ([client mount]
   (read-config client mount nil)))


(defn delete-secret!
  "Performs a soft delete a secret. This marks the versions as deleted and will stop them from being returned from
  reads, but the underlying data will not be removed. A delete can be undone using the `undelete` path.

  - `client`: the Vault client you wish to delete a secret in
  - `mount`: the Vault secret mount (the part of the path which determines which secret engine is used)
  - `path`: the path aligned to the secret you wish to delete
  - `versions`: vector of the versions of that secret you wish to delete, defaults to deleting the latest version"
  ([client mount path versions]
   (if (empty? versions)
     (vault/delete-secret! client (str mount "/data/" path))
     (vault/write-secret! client (str mount "/delete/" path) versions)))
  ([client mount path]
   (delete-secret! client mount path nil)))
