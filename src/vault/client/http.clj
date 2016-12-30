(ns vault.client.http
  (:require
    [clj-http.client :as http]
    [clojure.string :as str]
    [clojure.tools.logging :as log]
    [com.stuartsierra.component :as component]
    (vault
      [core :as vault]
      [store :as store]
      [timer :as timer])))


;; ## API Utilities

(defn- check-path!
  "Validates that the given path is a non-empty string."
  [path]
  (when-not (and (string? path) (not (empty? path)))
    (throw (IllegalArgumentException.
             (str "Secret path must be a non-empty string, got: "
                  (pr-str path))))))


(defn- check-auth!
  "Validates that the client is authenticated."
  [auth-ref]
  (when-not (:client-token @auth-ref)
    (throw (IllegalStateException.
             "Cannot read path with unauthenticated client."))))


(defn- do-api-request
  "Performs a request against the API, following redirects at most twice. The
  `api-url` should be the base server endpoint for Vault, and `path` should be
  relative from the version root. Currently always uses API version `v1`."
  [method request-url req]
  (let [redirects (::redirects (meta req) 0)]
    (when (<= 2 redirects)
      (throw (ex-info (str "Aborting Vault API request after " redirects " redirects")
                      {:method method, :url request-url})))
    (let [resp (http/request (assoc req :method method :url request-url))]
      (if-let [location (and (#{303 307} (:status resp)) (get-in resp [:headers "Location"]))]
        (do (log/debug "Retrying API request redirected to " location)
            (recur method location (vary-meta req assoc ::redirects (inc redirects))))
        resp))))


(defn- api-request
  "Helper method to perform an API request with common headers and values."
  [client method path req]
  (check-path! path)
  (do-api-request
    method
    (str (:api-url client) "/v1/" path)
    (merge
      {:accept :json
       :as :json}
      req
      {:headers (merge {"X-Vault-Token" (:client-token @(:auth client))}
                       (:headers req))})))



;; ## Authentication Methods

(defn- api-auth!
  [claim auth-ref response]
  (let [auth-info (get-in response [:body :auth])]
    (when-not (:client_token auth-info)
      (throw (ex-info (str "No client token returned from non-error API response: "
                           (:status response) " " (:reason-phrase response))
                      {:body (:body response)})))
    (log/infof "Successfully authenticated to Vault as %s for policies: %s"
               claim (str/join ", " (:policies auth-info)))
    (reset! auth-ref auth-info)))


(defn- authenticate-token!
  "Updates the token ref by storing the given auth token."
  [auth-ref token]
  (when-not (string? token)
    (throw (IllegalArgumentException. "Token credential must be a string")))
  (reset! auth-ref {:client-token (str/trim token)}))


(defn- authenticate-userpass!
  "Updates the token ref by making a request to authenticate with a username
  and password."
  [auth-ref api-url credentials]
  (let [{:keys [username password]} credentials]
    (api-auth!
      (str "user " username)
      auth-ref
      (do-api-request :post (str api-url "/v1/auth/userpass/login/" username)
        {:form-params {:password password}
         :content-type :json
         :accept :json
         :as :json}))))


(defn- authenticate-app!
  "Updates the token ref by making a request to authenticate with an app-id and
  secret user-id."
  [auth-ref api-url credentials]
  (let [{:keys [app user]} credentials]
    (api-auth!
      (str "app-id " app)
      auth-ref
      (do-api-request :post (str api-url "/v1/auth/app-id/login")
        {:form-params {:app_id app, :user_id user}
         :content-type :json
         :accept :json
         :as :json}))))



;; ## HTTP Client Type

;; - `:api-url`
;;   The base URL for the Vault API endpoint.
;; - `:auth`
;;   An atom containing the authentication lease information, including the
;;   client token.
;; - `:store`
;;   Local in-memory storage of secret leases.
;; - `:lease-timer`
;;   Thread which periodically checks and renews leased secrets.
;; - `:lease-renewal-window
;;   Period in seconds to renew leases before they expire.
;; - `:lease-check-period`
;;   Period in seconds to check for leases to renew.
;; - `:lease-check-jitter`
;;   Maximum amount in seconds to jitter the check period by.
(defrecord HTTPClient
  [api-url auth store lease-timer]

  component/Lifecycle

  (start
    [this]
    (if lease-timer
      ; Already running
      this
      ; Start lease heartbeat thread.
      (let [window (:lease-renewal-window this 1200)  ; 20 minutes
            period (:lease-check-period   this  300)  ;  5 minutes
            jitter (:lease-check-jitter   this   60)  ;  1 minute
            thread (timer/start! "vault-lease-timer"
                                 #(store/renew-leases!
                                    (partial vault/renew-lease this)
                                    store
                                    window)
                                 period
                                 jitter)]
        (assoc this :lease-timer thread))))

  (stop
    [this]
    (if lease-timer
      ; Stop lease timer thread.
      (do
        (timer/stop! lease-timer)
        ; TODO: revoke all outstanding leases
        (assoc this :lease-timer nil))
      ; Already stopped.
      this))


  vault/Client

  (authenticate!
    [this auth-type credentials]
    (case auth-type
      :token (authenticate-token! auth credentials)
      :app-id (authenticate-app! auth api-url credentials)
      :userpass (authenticate-userpass! auth api-url credentials)
      ; Unknown type
      (throw (ex-info (str "Unsupported auth-type " (pr-str auth-type))
                      {:auth-type auth-type})))
    this)

  ; TODO: status


  vault/TokenManager

  (create-token!
    [this]
    (.create-token! this nil))

  (create-token!
    [this opts]
    (check-auth! auth)
    (let [response (api-request this :post "auth/token/create"
                     {:headers (when-let [ttl (:wrap-ttl opts)]
                                 {"X-Vault-Wrap-TTL" ttl})})]
      (log/debug "Created token" (when-let [ttl (:wrap-ttl opts)] "with X-Vault-Wrap-TTL" ttl))
      (when (= (:status response) 200)
        (:body response))))

  ; TODO: lookup-token
  ; TODO: lookup-accessor
  ; TODO: renew-token
  ; TODO: revoke-token!
  ; TODO: revoke-accessor!


  vault/LeaseManager

  ; TODO: list-leases
  ; TODO: renew-lease
  ; TODO: revoke-lease!


  vault/SecretClient

  (list-secrets
    [this path]
    (check-auth! auth)
    (let [response (api-request this :get path
                     {:query-params {:list true}})
          data (get-in response [:body :data :keys])]
      (log/debugf "List %s (%d results)" path (count data))
      data))

  (read-secret
    [this path]
    (.read-secret this path nil))

  (read-secret
    [this path opts]
    (let [info (or (store/lookup store path)
                   (let [response (api-request this :get path {})]
                     (log/debugf "Read %s (valid for %d seconds)"
                                 path (get-in response [:body :lease_duration]))
                     (store/store! store path (:body response))))]
      (when-not info
        (log/warn "No value found for secret" path))
      (:data info)))

  (write-secret!
    [this path data]
    (check-auth! auth)
    (let [response (api-request this :post path
                     {:form-params data
                      :content-type :json})]
      (log/debug "Wrote secret" path)
      (store/invalidate! store path)
      (= (:status response) 204)))

  (delete-secret!
    [this path]
    (check-auth! auth)
    (let [response (api-request this :delete path {})]
      (log/debug "Deleted secret" path)
      (store/invalidate! store path)
      (= (:status response) 204)))


  vault/WrappingClient

  ; TODO: wrap!

  (unwrap!
    [this wrap-token]
    (let [response (api-request this :post "sys/wrapping/unwrap"
                     {:headers {"X-Vault-Token" wrap-token}})]
      (log/debug "Unwrapping response")
      (when (= (:status response) 200)
        (:body response)))))



;; ## Constructors

;; Privatize automatic constructors.
(alter-meta! #'->HTTPClient assoc :private true)
(alter-meta! #'map->HTTPClient assoc :private true)


(defn http-client
  "Constructs a new HTTP Vault client."
  [api-url & {:as opts}]
  (when-not (and (string? api-url) (str/starts-with? api-url "http"))
    (throw (IllegalArgumentException.
             (str "Vault api-url must be a string starting with 'http', got: "
                  (pr-str api-url)))))
  (map->HTTPClient
    (merge opts {:api-url api-url
                 :auth (atom nil)
                 :leases (store/new-store)})))


(defmethod vault/new-client "http"
  [location]
  (http-client location))


(defmethod vault/new-client "https"
  [location]
  (http-client location))
