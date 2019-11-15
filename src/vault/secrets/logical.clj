(ns vault.secrets.logical
  (:require
    [vault.core :as vault]))


(defn list-secrets
  [client path]
  (vault/list-secrets client path))


(defn read-secret
  ([client path opts]
   (vault/read-secret client path opts))
  ([client path]
   (read-secret client path nil)))


(defn write-secret!
  [client path data]
  (vault/write-secret! client path data))


(defn delete-secret!
  [client path]
  (vault/delete-secret! client path))
