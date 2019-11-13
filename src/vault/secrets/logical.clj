(ns vault.secrets.logical
  (:require
    [clojure.tools.logging :as log]
    [vault.client.http :as http-client]
    [vault.lease :as lease]
    [vault.secret-engines :as engine]
    [vault.secrets.dispatch :refer [list-secrets* read-secret* write-secret!* delete-secret!*]])
  (:import
    (clojure.lang
      ExceptionInfo)))


(defn list-secrets
  [client path]
  (engine/list-secrets client path :logical))


(defmethod list-secrets* :logical
  [client path _]
  (let [response (http-client/api-request
                   client :get path
                   {:query-params {:list true}})
        data (get-in response [:body :data :keys])]
    (log/debugf "List %s (%d results)" path (count data))
    data))


(defn read-secret
  ([client path opts]
   (engine/read-secret client path opts :logical))
  ([client path]
   (read-secret client path nil)))


(defmethod read-secret* :logical
  [client path opts _]
  (or (when-let [lease (and (not (:force-read opts))
                            (lease/lookup (:leases client) path))]
        (when-not (lease/expired? lease)
          (:data lease)))
      (try
        (let [response (http-client/api-request client :get path {})
              info (assoc (http-client/clean-body response)
                          :path path
                          :renew (:renew opts)
                          :rotate (:rotate opts))]
          (log/debugf "Read %s (valid for %d seconds)"
                      path (:lease-duration info))
          (lease/update! (:leases client) info)
          (:data info))
        (catch ExceptionInfo ex
          (if (and (contains? opts :not-found)
                   (= ::http-client/api-error (:type (ex-data ex)))
                   (= 404 (:status (ex-data ex))))
            (:not-found opts)
            (throw ex))))))


(defn write-secret!
  [client path data]
  (engine/write-secret! client path data :logical))


(defmethod write-secret!* :logical
  [client path data _]
  (let [response (http-client/api-request
                   client :post path
                   {:form-params data
                    :content-type :json})]
    (log/debug "Wrote secret" path)
    (lease/remove-path! (:leases client) path)
    (case (int (:status response -1))
      204 true
      200 (:body response)
      false)))


(defn delete-secret!
  [client path]
  (engine/delete-secret! client path :logical))


(defmethod delete-secret!* :logical
  [client path _]
  (let [response (http-client/api-request client :delete path {})]
    (log/debug "Deleted secret" path)
    (lease/remove-path! (:leases client) path)
    (= 204 (:status response))))
