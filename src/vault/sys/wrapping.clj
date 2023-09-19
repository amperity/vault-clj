(ns vault.sys.wrapping
  "The `/sys/wrapping` endpoint is used to wrap secrets and lookup, rewrap, and
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
    "Read the wrapping properties for the given token.")

  (rewrap
    [client token-id]
    "Rotate the given wrapping token and refresh its TTL. Returns the new token
    info.")

  (unwrap
    [client]
    [client token-id]
    "Read the original response inside the given wrapping token.")

  (wrap
    [client data ttl]
    "Wrap the given map of data inside a response-wrapped token with the
    specified time-to-live. The TTL can be either an integer number of seconds
    or a string duration such as `15s`, `20m`, `25h`, etc. Returns the new
    token info."))


;; ## HTTP Client

(defn- kebabify-body-wrap-info
  [body]
  (u/kebabify-keys (get body "wrap_info")))


(extend-type HTTPClient

  API

  (lookup
    [client token-id]
    (http/call-api
      client ::lookup
      :post "sys/wrapping/lookup"
      {:content-type :json
       :body {:token token-id}
       :handle-response u/kebabify-body-data}))


  (rewrap
    [client token-id]
    (http/call-api
      client ::rewrap
      :post "sys/wrapping/rewrap"
      {:content-type :json
       :body {:token token-id}
       :handle-response kebabify-body-wrap-info}))


  (unwrap
    ([client]
     (http/call-api
       client ::unwrap
       :post "sys/wrapping/unwrap"
       {:content-type :json
        :handle-response (some-fn u/kebabify-body-auth
                                  u/kebabify-body-data)}))
    ([client token-id]
     (http/call-api
       client ::unwrap
       :post "sys/wrapping/unwrap"
       {:content-type :json
        :body {:token token-id}
        :handle-response (some-fn u/kebabify-body-auth
                                  u/kebabify-body-data)})))


  (wrap
    [client data ttl]
    (when-not (map? data)
      (throw (IllegalArgumentException. "Data to wrap must be a map.")))
    (http/call-api
      client ::wrap
      :post "sys/wrapping/wrap"
      {:headers {"X-Vault-Wrap-TTL" ttl}
       :content-type :json
       :body data
       :handle-response kebabify-body-wrap-info})))
