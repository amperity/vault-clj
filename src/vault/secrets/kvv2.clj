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

