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
  [token-ref]
  (when-not @token-ref
    (throw (IllegalStateException.
             "Cannot read path with unauthenticated client."))))


(defn- api-headers
  ([token]
   (api-headers token {}))
  ([token opts]
   (merge {"X-Vault-Token" token}
          (when-let [wrap-ttl (:wrap-ttl opts)]
            {"X-Vault-Wrap-TTL" wrap-ttl}))))


(defn- api-request
  "Performs a request against the API, following redirects at most twice."
  [method url req]
  (let [redirects (::redirects (meta req) 0)]
    (when (<= 2 redirects)
      (throw (ex-info (str "Aborting Vault API request after " redirects " redirects")
                      {:method method, :url url})))
    (let [resp (http/request (assoc req :method method :url url))]
      (if-let [location (and (#{303 307} (:status resp)) (get-in resp [:headers "Location"]))]
        (do (log/debug "Retrying API request redirected to " location)
            (recur method location (vary-meta req assoc ::redirects (inc redirects))))
        resp))))



;; ## Authentication Methods

(defn- authenticate-token!
  "Updates the token ref by storing the given auth token."
  [token-ref token]
  (when-not (string? token)
    (throw (IllegalArgumentException. "Token credential must be a string")))
  (reset! token-ref (str/trim token)))


(defn- authenticate-userpass!
  "Updates the token ref by making a request to authenticate with a username
  and password."
  [api-url token-ref credentials]
  (let [{:keys [username password]} credentials
        response (api-request :post (str api-url "/v1/auth/userpass/login/" username)
                   {:form-params {:password password}
                    :content-type :json
                    :accept :json
                    :as :json})]
    (let [client-token (get-in response [:body :auth :client_token])]
      (when-not client-token
        (throw (ex-info (str "No client token returned from non-error API response: "
                             (:status response) " " (:reason-phrase response))
                        {:body (:body response)})))
      (log/infof "Successfully authenticated to Vault as %s for policies: %s"
                 username (str/join ", " (get-in response [:body :auth :policies])))
      (reset! token-ref client-token))))


(defn- authenticate-app!
  "Updates the token ref by making a request to authenticate with an app-id and
  secret user-id."
  [api-url token-ref credentials]
  (let [{:keys [app user]} credentials
        response (api-request :post (str api-url "/v1/auth/app-id/login")
                   {:form-params {:app_id app, :user_id user}
                    :content-type :json
                    :accept :json
                    :as :json})]
    (let [client-token (get-in response [:body :auth :client_token])]
      (when-not client-token
        (throw (ex-info (str "No client token returned from non-error API response: "
                             (:status response) " " (:reason-phrase response))
                        {:body (:body response)})))
      (log/infof "Successfully authenticated to Vault app-id %s for policies: %s"
                 app (str/join ", " (get-in response [:body :auth :policies])))
      (reset! token-ref client-token))))



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
      :token (authenticate-token! token credentials)
      :app-id (authenticate-app! api-url token credentials)
      :userpass (authenticate-userpass! api-url token credentials)
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
    [this wrap-ttl]
    (check-auth! token)
    (let [response (api-request :post (str api-url "/v1/auth/token/create")
                     {:headers (api-headers @token {:wrap-ttl wrap-ttl})
                      :accept :json
                      :as :json})]
      (log/debug "Created token" (when wrap-ttl "with X-Vault-Wrap-TTL" wrap-ttl))
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
    (check-path! path)
    (check-auth! token)
    (let [response (api-request :get (str api-url "/v1/" path)
                     {:headers (api-headers @token)
                      :query-params {:list true}
                      :accept :json
                      :as :json})
          data (get-in response [:body :data :keys])]
      (log/debugf "List %s (%d results)" path (count data))
      data))

  (read-secret
    [this path]
    (.read-secret this path nil))

  (read-secret
    [this path opts]
    (check-path! path)
    (check-auth! token)
    (let [info (or (store/lookup store path)
                   (let [response (api-request :get (str api-url "/v1/" path)
                                    {:headers (api-headers @token)
                                     :accept :json
                                     :as :json})]
                     (log/debugf "Read %s (valid for %d seconds)"
                                 path (get-in response [:body :lease_duration]))
                     (store/store! store path (:body response))))]
      (when-not info
        (log/warn "No value found for secret" path))
      (:data info)))

  (write-secret!
    [this path data]
    (check-path! path)
    (check-auth! token)
    (let [response (api-request :post (str api-url "/v1/" path)
                     {:headers (api-headers @token)
                      :form-params data
                      :content-type :json
                      :accept :json
                      :as :json})]
      (log/debug "Wrote secret" path)
      (store/invalidate! store path)
      (= (:status response) 204)))

  (delete-secret!
    [this path]
    (check-path! path)
    (check-auth! token)
    (let [response (api-request :delete (str api-url "/v1/" path)
                     {:headers (api-headers @token)
                      :accept :json
                      :as :json})]
      (log/debug "Deleted secret" path)
      (store/invalidate! store path)
      (= (:status response) 204)))


  vault/WrappingClient

  ; TODO: wrap!

  (unwrap!
    [this wrap-token]
    (let [response (api-request :post (str api-url "/v1/sys/wrapping/unwrap")
                     {:headers (api-headers wrap-token)
                      :accept :json
                      :as :json})]
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
                 :token (atom nil)
                 :leases (store/new-store)})))


(defmethod vault/new-client "http"
  [location]
  (http-client location))


(defmethod vault/new-client "https"
  [location]
  (http-client location))
