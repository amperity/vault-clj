(ns vault.sys.auth
  "The /sys/auth endpoint is used to list, create, update, and delete auth
  methods. Auth methods convert user or machine-supplied information into a
  token which can be used for all future requests.

  Reference: https://www.vaultproject.io/api-docs/system/auth"
  (:require
    [clojure.string :as str]
    [vault.client.http :as http]
    [vault.client.mock :as mock]
    [vault.client.util :as u])
  (:import
    vault.client.http.HTTPClient
    vault.client.mock.MockClient))


;; ## API Protocol

(defprotocol API
  "The health endpoint is used to check the health status of Vault."

  (list-methods
    [client]
    "Lists all enabled auth methods. Returns a map of endpoints to their
    configuration data.")

  (enable-method!
    [client path params]
    "Enables a new auth method. After enabling, the auth method can be accessed
    and configured via the auth path specified as part of the URL. This auth
    path will be nested under the `auth/` prefix. Returns nil.")

  (disable-method!
    [client path]
    "Disables the auth method at the given auth path. Returns nil.")

  (read-method-tuning
    [client path]
    "Reads the given auth path's configuration.")

  (tune-method!
    [client path params]
    "Tune configuration parameters for a given auth path."))


;; ## Mock Client

(extend-type MockClient

  API

  (list-methods
    [client]
    (mock/success-response
      client
      {"token/" {:accessor "auth_token_96109b84"
                 :config {:default-lease-ttl 0
                          :force-no-cache false
                          :max-lease-ttl 0
                          :token-type "default-service"}
                 :description "token based credentials"
                 :external-entropy-access false
                 :local false
                 :options nil
                 :seal-wrap false
                 :type "token"
                 :uuid "fcd3aea9-d682-3143-72d3-938c3f666d62"}}))


  (read-method-tuning
    [client path]
    (if (= "token/" path)
      (mock/success-response
        client
        {:default-lease-ttl 2764800,
         :description "token based credentials",
         :force-no-cache false,
         :max-lease-ttl 2764800,
         :token-type "default-service"})
      (mock/error-response
        client
        (let [error (str "cannot fetch sysview for path \"" path \")]
          (ex-data
            (str "Vault API errors: " error)
            {:vault.client/errors [error]
             :vault.client/status 400}))))))


;; ## HTTP Client

(extend-type HTTPClient

  API

  (list-methods
    [client]
    (http/call-api
      client :get "sys/auth"
      {:handle-response
       (fn handle-response
         [body]
         (into {}
               (map (juxt key (comp u/kebabify-keys val)))
               (get body "data")))}))


  (enable-method!
    [client path params]
    (http/call-api
      client :post (str "sys/auth/" path)
      {:content-type :json
       :body (u/snakify-keys params)}))


  (disable-method!
    [client path]
    (http/call-api
      client :delete (u/join-path "sys/auth" path)
      {}))


  (read-method-tuning
    [client path]
    (http/call-api
      client :get (u/join-path "sys/auth" path "tune")
      {:handle-response
       (fn handle-response
         [body]
         (u/kebabify-keys (get body "data")))}))


  (tune-method!
    [client path params]
    (http/call-api
      client :post (u/join-path "sys/auth" path "tune")
      {:content-type :json
       :body (u/snakify-keys params)})))
