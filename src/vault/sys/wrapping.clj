(ns vault.sys.wrapping
  "The /sys/wrapping endpoint is used to wrap secrets and lookup, rewrap, and
  unwrap tokens.

  Reference:
  - https://www.vaultproject.io/api-docs/system/wrapping-lookup
  - https://www.vaultproject.io/api-docs/system/wrapping-rewrap
  - https://www.vaultproject.io/api-docs/system/wrapping-unwrap
  - https://www.vaultproject.io/api-docs/system/wrapping-wrap"
  (:require
    [vault.client.http :as http]
    [vault.util :as u])
  (:import
    vault.client.http.HTTPClient))


;; ## API Protocol

(defprotocol API
  "The wrapping endpoint is used to manage response-wrapped tokens."

  (lookup
    [client token-id]
    "Returns the wrapping properties for the given token.")

  (rewrap
    [client token-id]
    "Rotates the given wrapping token and refreshes its TTL. Returns the new
    token info.")

  (unwrap
    [client]
    [client token-id]
    "Returns the original response inside the given wrapping token.")

  (wrap
    [client data ttl]
    "Wraps the given map of data inside a response-wrapped token with the
    specified TTL. The TTL can be either an integer number of seconds or a
    string duration of seconds (15s), minutes (20m), or hours (25h). Returns the
    new token info."))


;; ## HTTP Client

(defn- kebabify-body-wrap-info
  [body]
  (u/kebabify-keys (get body "wrap_info")))


(extend-type HTTPClient

  API

  (lookup
    [client token-id]
    (http/call-api
      client :post "sys/wrapping/lookup"
      {:content-type :json
       :body {:token token-id}
       :handle-response u/kebabify-body-data}))


  (rewrap
    [client token-id]
    (http/call-api
      client :post "sys/wrapping/rewrap"
      {:content-type :json
       :body {:token token-id}
       :handle-response kebabify-body-wrap-info}))


  (unwrap
    ([client]
     (http/call-api
       client :post "sys/wrapping/unwrap"
       {:content-type :json
        :handle-response u/kebabify-body-auth}))
    ([client token-id]
     (http/call-api
       client :post "sys/wrapping/unwrap"
       {:content-type :json
        :body {:token token-id}
        :handle-response u/kebabify-body-auth})))


  (wrap
    [client data ttl]
    (when-not (map? data)
      (throw (IllegalArgumentException. "Data to wrap must be a map.")))
    (http/call-api
      client :post "sys/wrapping/wrap"
      {:headers {"X-Vault-Wrap-TTL" ttl}
       :content-type :json
       :body data
       :handle-response kebabify-body-wrap-info})))
