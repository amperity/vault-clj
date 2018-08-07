(ns vault.client.http
  (:require
    [cheshire.core :as json]
    [clj-http.client :as http]
    [clojure.string :as str]
    [clojure.tools.logging :as log]
    [clojure.walk :as walk]
    [com.stuartsierra.component :as component]
    (vault
      [core :as vault]
      [lease :as lease]
      [timer :as timer])))


;; ## API Utilities

(defn- kebabify-keys
  "Rewrites keyword map keys with underscores changed to dashes."
  [value]
  (let [kebab-kw #(-> % name (str/replace "_" "-") keyword)
        xf-entry (juxt (comp kebab-kw key) val)]
    (walk/postwalk
      (fn xf-maps [x]
        (if (map? x)
          (into {} (map xf-entry) x)
          x))
      value)))


(defn- clean-body
  "Cleans up a response from the Vault API by rewriting some keywords and
  dropping extraneous information. Note that this changes the `:data` in the
  response to the original result to preserve accuracy."
  [response]
  (->
    (:body response)
    (kebabify-keys)
    (assoc :data (:data (:body response)))
    (->> (into {} (filter (comp some? val))))))


(defn- api-error
  "Inspects an exception and returns a cleaned-up version if the type is well
  understood. Otherwise returns the original error."
  [ex]
  (let [data (ex-data ex)
        status (:status data)]
    (if (and status (<= 400 status))
      (let [body (try
                   (json/parse-string (:body data) true)
                   (catch Exception _
                     nil))
            errors (if (:errors body)
                     (str/join ", " (:errors body))
                     (pr-str body))]
        (ex-info (str "Vault API errors: " errors)
                 {:type ::api-error
                  :status status
                  :errors (:errors body)}
                 ex))
      ex)))


(defn- do-api-request
  "Performs a request against the API, following redirects at most twice. The
  `request-url` should be the full API endpoint."
  [method request-url req]
  (let [redirects (::redirects req 0)]
    (when (<= 2 redirects)
      (throw (ex-info (str "Aborting Vault API request after " redirects " redirects")
                      {:method method, :url request-url})))
    (let [resp (try
                 (http/request (assoc req :method method :url request-url))
                 (catch Exception ex
                   (throw (api-error ex))))]
      (if-let [location (and (#{303 307} (:status resp))
                             (get-in resp [:headers "Location"]))]
        (do (log/debug "Retrying API request redirected to " location)
            (recur method location (assoc req ::redirects (inc redirects))))
        resp))))


(defn- api-request
  "Helper method to perform an API request with common headers and values.
  Currently always uses API version `v1`. The `path` should be relative to the
  version root."
  [client method path req]
  ; Check API path.
  (when-not (and (string? path) (not (empty? path)))
    (throw (IllegalArgumentException.
             (str "API path must be a non-empty string, got: "
                  (pr-str path)))))
  ; Check client authentication.
  (when-not (or
              (some-> req :headers keys set (contains? "X-Vault-Token"))
              (some-> client :auth deref :client-token))
    (throw (IllegalStateException.
             "Cannot call API path with unauthenticated client.")))
  ; Call API with standard arguments.
  (do-api-request
    method
    (str (:api-url client) "/v1/" path)
    (merge
      (:http-opts client)
      {:accept :json
       :as :json}
      (dissoc req :headers)
      {:headers (merge {"X-Vault-Token" (:client-token @(:auth client))}
                       (:headers req))})))



;; ## Authentication Methods

(defn- api-auth!
  [claim auth-ref response]
  (let [auth-info (lease/auth-lease (:auth (clean-body response)))]
    (when-not (:client-token auth-info)
      (throw (ex-info (str "No client token returned from non-error API response: "
                           (:status response) " " (:reason-phrase response))
                      {:body (:body response)})))
    (log/infof "Successfully authenticated to Vault as %s for policies: %s"
               claim (str/join ", " (:policies auth-info)))
    (reset! auth-ref auth-info)))


(defn- authenticate-token!
  "Updates the token ref by storing the given auth token."
  [client token-map]
  (let [token (:client-token token-map)]
    (when-not (string? token)
      (throw (IllegalArgumentException. "Token credential must be a string")))
    (reset! (:auth client) {:client-token (str/trim token)})))


(defn- authenticate-userpass!
  "Updates the token ref by making a request to authenticate with a username
  and password."
  [client credentials]
  (let [{:keys [username password]} credentials]
    (api-auth!
      (str "user " username)
      (:auth client)
      (do-api-request :post (str (:api-url client) "/v1/auth/userpass/login/" username)
        (merge
          (:http-opts client)
          {:form-params {:password password}
           :content-type :json
           :accept :json
           :as :json})))))


(defn- authenticate-app!
  "Updates the token ref by making a request to authenticate with an app-id and
  secret user-id."
  [client credentials]
  (let [{:keys [app user]} credentials]
    (api-auth!
      (str "app-id " app)
      (:auth client)
      (do-api-request :post (str (:api-url client) "/v1/auth/app-id/login")
        (merge
          (:http-opts client)
          {:form-params {:app_id app, :user_id user}
           :content-type :json
           :accept :json
           :as :json})))))

(defn- authenticate-app-role!
  "Updates the token ref by making a request to authenticate with an role-id and
  secret-id."
  [client credentials]
  (let [{:keys [role-id secret-id]} credentials]
    (api-auth!
      (str "role-id " role-id)
      (:auth client)
      (do-api-request :post (str (:api-url client) "/v1/auth/approle/login")
        (merge
          (:http-opts client)
          {:form-params {:role_id role-id, :secret_id secret-id}
           :content-type :json
           :accept :json
           :as :json})))))


(defn- authenticate-ldap!
  "Updates the token ref by making a request to authenticate with a username
  and password, to be authenticated against an LDAP backend."
  [client credentials]
  (let [{:keys [username password]} credentials]
    (api-auth!
      (str "LDAP user " username)
      (:auth client)
      (do-api-request :post (str (:api-url client) "/v1/auth/ldap/login/" username)
        (merge
          (:http-opts client)
          {:form-params {:password password}
           :content-type :json
           :accept :json
           :as :json})))))


;; ## Timer Logic

(defn- try-renew-lease!
  "Attempts to renew the given secret lease. Updates the lease store or catches
  and logs any exception."
  [client secret]
  (try
    (vault/renew-lease client (:lease-id secret))
    (catch Exception ex
      (log/error ex "Failed to renew secret lease" (:lease-id secret)))))


(defn- try-rotate-secret!
  "Attempts to rotate the given secret lease. Updates the lease store or catches
  and logs any exception."
  [client secret]
  (try
    (log/info "Rotating secret lease" (:lease-id secret))
    (let [response (api-request client :get (:path secret) {})
          info (assoc (clean-body response) :path (:path secret))]
      (lease/update! (:leases client) info))
    (catch Exception ex
      (log/error ex "Failed to rotate secret" (:lease-id secret)))))


(defn- maintain-leases!
  [client window]
  (log/trace "Checking for renewable leases...")
  ; Check auth token for renewal.
  (let [auth @(:auth client)]
    (when (and (:renewable auth)
               (lease/expires-within? auth window)
               (some :lease-id (lease/list-leases (:leases client))))
      (try
        (log/info "Renewing Vault client token")
        (vault/renew-token client)
        (catch Exception ex
          (log/error ex "Failed to renew client token!")))))
  ; Renew leases that are within expiry window and are configured for renewal.
  ; Rotate secrets that are about to expire and not renewable.
  (let [renewable (lease/renewable-leases (:leases client) window)
        rotatable (lease/rotatable-leases (:leases client) window)]
    (doseq [secret renewable]
      (try-renew-lease! client secret))
    ; Rotate leases that are within expiry window and not renewable.
    (doseq [secret rotatable]
      (try-rotate-secret! client secret)))
  ; Drop any expired leases.
  (lease/sweep! (:leases client)))


(defn- unwrap*
  [this wrap-token]
  (let [response (api-request this :post "sys/wrapping/unwrap"
                              {:headers {"X-Vault-Token" wrap-token}})]
    (or (-> response :body :auth kebabify-keys)
        (-> response :body :data)
        (throw (ex-info "No auth info or data in response body"
                        {:body (:body response)})))))


;; ## HTTP Client Type

;; - `api-url`
;;   The base URL for the Vault API endpoint.
;; - `http-opts`
;;   Extra options to pass to `clj-http` requests.
;; - `auth`
;;   An atom containing the authentication lease information, including the
;;   client token.
;; - `leases`
;;   Local in-memory storage of secret leases.
;; - `lease-timer`
;;   Thread which periodically checks and renews leased secrets.
(defrecord HTTPClient
  [api-url http-opts auth leases lease-timer]

  component/Lifecycle

  (start
    [this]
    (if lease-timer
      ; Already running
      this
      ; Start lease heartbeat thread.
      (let [window (:lease-renewal-window this 300)
            period (:lease-check-period   this  60)
            jitter (:lease-check-jitter   this  10)
            thread (timer/start! "vault-lease-timer"
                                 #(maintain-leases! this window)
                                 period
                                 jitter)]
        (assoc this :lease-timer thread))))

  (stop
    [this]
    (if lease-timer
      (do
        ; Stop lease timer thread.
        (timer/stop! lease-timer)
        ; Revoke all outstanding leases.
        (when-let [outstanding (and (:revoke-on-stop? this)
                                    (seq (filter lease/leased? (vault/list-leases this))))]
          (log/infof "Revoking %d outstanding secret leases" (count outstanding))
          (doseq [secret outstanding]
            (try
              (vault/revoke-lease! this (:lease-id secret))
              (catch Exception ex
                (log/error ex "Failed to revoke lease" (:lease-id secret))))))
        (assoc this :lease-timer nil))
      ; Already stopped.
      this))


  vault/Client

  (authenticate!
    [this auth-type credentials]
    (case auth-type
      :token (authenticate-token! this {:client-token credentials})
      :wrap-token (authenticate-token! this (unwrap* this credentials))
      :app-id (authenticate-app! this credentials)
      :app-role (authenticate-app-role! this credentials)
      :userpass (authenticate-userpass! this credentials)
      :ldap (authenticate-ldap! this credentials)
      ; Unknown type
      (throw (ex-info (str "Unsupported auth-type " (pr-str auth-type))
                      {:auth-type auth-type})))
    this)

  (status
    [this]
    (-> (do-api-request :get (str api-url "/v1/sys/health")
          (assoc http-opts
                 :accept :json
                 :as :json))
        (clean-body)))


  vault/TokenManager

  (create-token!
    [this opts]
    (let [params (->> (dissoc opts :wrap-ttl)
                      (map (fn [[k v]] [(str/replace (name k) "-" "_") v]))
                      (into {}))
          response (api-request this :post "auth/token/create"
                     {:headers (when-let [ttl (:wrap-ttl opts)]
                                 {"X-Vault-Wrap-TTL" ttl})
                      :form-params params
                      :content-type :json})]
      ; Return auth info if available, or wrap info if not.
      (or (-> response :body :auth kebabify-keys)
          (-> response :body :wrap_info kebabify-keys)
          (throw (ex-info "No auth or wrap-info in response body"
                          {:body (:body response)})))))

  (lookup-token
    [this]
    (-> (api-request this :get "auth/token/lookup-self" {})
        (get-in [:body :data])
        (kebabify-keys)))

  (lookup-token
    [this token]
    (-> (api-request this :post "auth/token/lookup"
          {:form-params {:token token}
           :content-type :json})
        (get-in [:body :data])
        (kebabify-keys)))

  (renew-token
    [this]
    (-> (api-request this :post "auth/token/renew-self" {})
        (clean-body)
        (:auth)))

  (renew-token
    [this token]
    (-> (api-request this :post "auth/token/renew"
          {:form-params {:token token}
           :content-type :json})
        (clean-body)
        (:auth)))

  (revoke-token!
    [this]
    (when-let [token (:client-token @auth)]
      (.revoke-token! this token)))

  (revoke-token!
    [this token]
    (let [response (api-request this :post "auth/token/revoke"
                     {:form-params {:token token}
                      :content-type :json})]
      (= 204 (:status response))))

  (lookup-accessor
    [this token-accessor]
    (-> (api-request this :post "auth/token/lookup-accessor"
          {:form-params {:accessor token-accessor}
           :content-type :json})
        (get-in [:body :data])
        (kebabify-keys)))

  (revoke-accessor!
    [this token-accessor]
    (let [response (api-request this :post "auth/token/revoke-accessor"
                     {:form-params {:accessor token-accessor}
                      :content-type :json})]
      (= 204 (:status response))))


  vault/LeaseManager

  (list-leases
    [this]
    (lease/list-leases leases))

  (renew-lease
    [this lease-id]
    (log/debug "Renewing lease" lease-id)
    (let [current (lease/lookup leases lease-id)
          response (api-request this :put "sys/renew"
                     {:form-params {:lease_id lease-id}
                      :content-type :json})]
      (as-> (clean-body response) info
        ; If the lease looks renewable but the lease-duration is shorter than the
        ; existing lease, we're up against the max-ttl and the lease should not
        ; be considered renewable.
        (if (and (lease/renewable? info)
                 (< (:lease-duration info)
                    (:lease-duration current)))
          (assoc info :renewable false)
          info)
        (lease/update! leases info))))

  (revoke-lease!
    [this lease-id]
    (log/debug "Revoking lease" lease-id)
    (let [response (api-request this :put (str "sys/revoke/" lease-id) {})]
      (lease/remove-lease! leases lease-id)
      (= 204 (:status response))))

  (add-lease-watch
    [this watch-key path watch-fn]
    (add-watch leases watch-key (lease/lease-watcher path watch-fn))
    this)

  (remove-lease-watch
    [this watch-key]
    (remove-watch leases watch-key)
    this)


  vault/SecretClient

  (list-secrets
    [this path]
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
    (or (when-let [lease (and (not (:force-read opts))
                              (lease/lookup leases path))]
          (when-not (lease/expired? lease)
            (:data lease)))
        (try
          (let [response (api-request this :get path {})
                info (assoc (clean-body response)
                            :path path
                            :renew (:renew opts)
                            :rotate (:rotate opts))]
            (log/debugf "Read %s (valid for %d seconds)"
                        path (:lease-duration info))
            (lease/update! leases info)
            (:data info))
          (catch clojure.lang.ExceptionInfo ex
            (if (and (contains? opts :not-found)
                     (= ::api-error (:type (ex-data ex)))
                     (= 404 (:status (ex-data ex))))
              (:not-found opts)
              (throw ex))))))

  (write-secret!
    [this path data]
    (let [response (api-request this :post path
                     {:form-params data
                      :content-type :json})]
      (log/debug "Wrote secret" path)
      (lease/remove-path! leases path)
      (case  (:status response)
        204 true
        200 (:body response)
        false)))
  

  (delete-secret!
    [this path]
    (let [response (api-request this :delete path {})]
      (log/debug "Deleted secret" path)
      (lease/remove-path! leases path)
      (= 204 (:status response))))


  vault/WrappingClient

  (wrap!
    [this data ttl]
    (-> (api-request this :post "sys/wrapping/wrap"
          {:headers {"X-Vault-Wrap-TTL" ttl}
           :form-params data
           :content-type :json})
        (get-in [:body :wrap_info])
        (kebabify-keys)))

  (unwrap!
    [this wrap-token]
    (unwrap* this wrap-token)))



;; ## Constructors

;; Privatize automatic constructors.
(alter-meta! #'->HTTPClient assoc :private true)
(alter-meta! #'map->HTTPClient assoc :private true)


(defn http-client
  "Constructs a new HTTP Vault client.

  Client behavior may be controlled with the options:

  - `:http-opts`
    Additional options to pass to `clj-http` requests.
  - `:lease-renewal-window`
    Period in seconds to renew leases before they expire.
  - `:lease-check-period`
    Period in seconds to check for leases to renew.
  - `:lease-check-jitter`
    Maximum amount in seconds to jitter the check period by.
  - `:revoke-on-stop?`
    Whether to revoke all outstanding leases when the client stops."
  [api-url & {:as opts}]
  (when-not (and (string? api-url) (str/starts-with? api-url "http"))
    (throw (IllegalArgumentException.
             (str "Vault api-url must be a string starting with 'http', got: "
                  (pr-str api-url)))))
  (map->HTTPClient
    (merge opts {:api-url api-url
                 :auth (atom nil)
                 :leases (lease/new-store)})))


(defmethod vault/new-client "http"
  [location]
  (http-client location))


(defmethod vault/new-client "https"
  [location]
  (http-client location))
