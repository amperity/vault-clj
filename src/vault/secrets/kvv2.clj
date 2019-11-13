(ns vault.secrets.kvv2
  (:require [vault.secret-engines :as engine]
            [vault.client.http :as http-client]
            [vault.lease :as lease]
            [clojure.tools.logging :as log]
            [clojure.string :as str]
            [vault.secrets.dispatch :refer [read-secret* write-config!* read-config*]]
            [vault.secrets.logical :as vault-logical])
  (:import (clojure.lang ExceptionInfo)))

(defn read-secret
  ([client mount path opts]
   (engine/read-secret client (str mount "/data/" path) opts :kvv2))
  ([client mount path]
   (read-secret client mount path nil)))

(defmethod read-secret* :kvv2
  [client path opts _]
  (try
    (:data (vault-logical/read-secret client path (dissoc opts :not-found)))

    (catch ExceptionInfo ex
      (if (and (contains? opts :not-found)
               (= ::api-error (:type (ex-data ex)))
               (= 404 (:status (ex-data ex))))
        ;(:not-found opts)
        (throw ex)))))

(defn write-secret!
  [client mount path data]
  (engine/write-secret! client (str mount "/data/" path) data :logical))


(defn write-config!
  [client mount data]
  (engine/write-config! client (str mount "/config") data :kvv2))


(defmethod write-config!* :kvv2
  [client path data _]
  (let [response (http-client/api-request
                   client :post path
                   {:form-params data
                    :content-type :json})]
    (log/debug "Wrote config" path)
    (case (int (:status response -1))
      204 true
      200 (:body response)
      false)))

(defn read-config
  [client mount]
  (-> (engine/read-config client (str mount "/config") :kvv2) :body :data))

(defmethod read-config* :kvv2
  [client path _]
  (http-client/api-request client :get path {}))

