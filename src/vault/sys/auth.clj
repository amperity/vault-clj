(ns vault.sys.auth
  "The `/sys/auth` endpoint is used to list, create, update, and delete auth
  methods. Auth methods convert user or machine-supplied information into a
  token which can be used for all future requests.

  Reference: https://www.vaultproject.io/api-docs/system/auth"
  (:require
    [vault.client.http :as http]
    [vault.client.mock :as mock]
    [vault.util :as u])
  (:import
    vault.client.http.HTTPClient
    vault.client.mock.MockClient))


;; ## API Protocol

(defprotocol API
  "The health endpoint is used to check the health status of Vault."

  (list-methods
    [client]
    "List all enabled auth methods. Returns a map of endpoints to their
    configurations.")

  (enable-method!
    [client path params]
    "Enable a new auth method at the given path under the `auth/` prefix. After
    enabling, the method can be accessed and configured via the specified path.
    Returns nil.

    Parameters:

    - `:type` (string)

      Name of the authentication method type, such as \"github\" or \"token\".

    - `:description` (optional, string)

      Human-friendly description of the auth method.

    - `:config` (optional, map)

      Configuration options for this auth method.

    See the Vault API docs for details.")

  (disable-method!
    [client path]
    "Disable the auth method at the given path. Returns nil.")

  (read-method-tuning
    [client path]
    "Read the tuning configuration for the auth method at the path. Returns a
    map of config.")

  (tune-method!
    [client path params]
    "Tune the configuration parameters for the auth method at the path. Returns
    `nil`.

    See the Vault API docs for available parameters."))


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
    (if (= "token" (u/trim-path path))
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
          (ex-info (str "Vault API errors: " error)
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
      client :post (u/join-path "sys/auth" path)
      {:info {::path path, ::type (:type params)}
       :content-type :json
       :body (u/snakify-keys params)}))


  (disable-method!
    [client path]
    (http/call-api
      client :delete (u/join-path "sys/auth" path)
      {:info {::path path}}))


  (read-method-tuning
    [client path]
    (http/call-api
      client :get (u/join-path "sys/auth" path "tune")
      {:info {::path path}
       :handle-response u/kebabify-body-data}))


  (tune-method!
    [client path params]
    (http/call-api
      client :post (u/join-path "sys/auth" path "tune")
      {:info {::path path}
       :content-type :json
       :body (u/snakify-keys params)})))
