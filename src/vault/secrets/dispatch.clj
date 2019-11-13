; Handles dispatch related to the functions from the secret engines protocol
(ns vault.secrets.dispatch)

; -- list ---------------------------------------------------------------------

(defmulti list-secrets*
  (fn dispatch [client path eng] eng))


(defmethod list-secrets* :default
  [client path eng]
  (throw (ex-info "list not supported by the secret engine" {:path path :engine eng})))

; -- read ---------------------------------------------------------------------

(defmulti read-secret*
  (fn dispatch [client path opts eng] eng))


(defmethod read-secret* :default
  [client path opts eng]
  (throw (ex-info "read not supported by the secret engine" {:path path :engine eng})))

; -- write! -------------------------------------------------------------------

(defmulti write-secret!*
  (fn [client path data eng] eng))


(defmethod write-secret!* :default
  [client path data eng]
  (throw (ex-info "write! not supported by the secret engine" {:path path :engine eng})))

; -- delete! ------------------------------------------------------------------

(defmulti delete-secret!*
  (fn [client path eng] eng))


(defmethod delete-secret!* :default
  [client path eng]
  (throw (ex-info "delete! not supported by the secret engine" {:path path :engine eng})))


; -- write-config! ------------------------------------------------------------------

(defmulti write-config!*
  (fn [client path data eng] eng))


(defmethod write-config!* :default
  [client path data eng]
  (throw (ex-info "write-config! not supported by the secret engine" {:path path :engine eng})))

; -- read-config ------------------------------------------------------------------

(defmulti read-config*
  (fn [client path eng] eng))


(defmethod read-config* :default
  [client path eng]
  (throw (ex-info "read-config not supported by the secret engine" {:path path :engine eng})))
