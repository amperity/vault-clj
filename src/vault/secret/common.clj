(ns ^:no-doc vault.secret.common
  "Common secret engine implementation utilities."
  (:require
    [vault.client.handler :as h]
    [vault.lease :as lease]
    [vault.sys.leases :as sys.leases]))


(defn renew-lease!
  "Renew the given lease."
  [client lease opts]
  (try
    (let [result (h/await
                   (:handler client)
                   (sys.leases/renew-lease!
                     client
                     (::lease/id lease)
                     (:renew-increment opts (* 4 60 60))))]
      (when-let [cb (:on-renew opts)]
        (try
          (cb result)
          (catch Exception _
            nil)))
      result)
    (catch Exception ex
      (when-let [cb (:on-error opts)]
        (try
          (cb ex)
          (catch Exception _
            nil)))
      nil)))


(defn rotate-secret!
  "Rotate a credential by calling `f`."
  [client f opts]
  (try
    (let [result (h/await (:handler client) (f))]
      (when-let [cb (:on-rotate opts)]
        (try
          (cb result)
          (catch Exception _
            nil)))
      true)
    (catch Exception ex
      (when-let [cb (:on-error opts)]
        (try
          (cb ex)
          (catch Exception _
            nil)))
      false)))


(defn renewable-lease
  "Apply common renewal settings to the lease map."
  ([lease client opts]
   (merge lease
          (when
            (and (::lease/renewable? lease)
                 (:renew? opts))
            {::lease/renew-within (:renew-within opts 60)
             ::lease/renew! #(renew-lease! client lease opts)}))))


(defn rotatable-lease
  "Apply common rotation settings to the lease map."
  ([lease client opts rotate-fn]
   (merge lease
          (when (and (:rotate? opts) rotate-fn)
            {::lease/rotate-within (:rotate-within opts 60)
             ::lease/rotate! #(rotate-secret! client rotate-fn opts)}))))
