(ns vault.client.http
  "Defines the Vault HTTP client and constructors"
  (:require
    [clojure.string :as str]
    [clojure.tools.logging :as log]
    [com.stuartsierra.component :as component]
    [vault.authenticate :as authenticate]
    [vault.client.api-util :as api-util]
    [vault.core :as vault]
    [vault.lease :as lease]
    [vault.timer :as timer])
  (:import
    (clojure.lang
      ExceptionInfo)))


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
                                 #(lease/maintain-leases! this window)
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
    (authenticate/authenticate* this auth-type credentials)
    this)


  (status
    [this]
    (-> (api-util/do-api-request
          :get (str api-url "/v1/sys/health")
          (assoc http-opts
                 :accept :json
                 :as :json))
        (api-util/clean-body)))


  vault/TokenManager

  (create-token!
    [this opts]
    (let [params (->> (dissoc opts :wrap-ttl)
                      (map (fn [[k v]] [(str/replace (name k) "-" "_") v]))
                      (into {}))
          response (api-util/api-request
                     this :post "auth/token/create"
                     {:headers (when-let [ttl (:wrap-ttl opts)]
                                 {"X-Vault-Wrap-TTL" ttl})
                      :form-params params
                      :content-type :json})]
      ; Return auth info if available, or wrap info if not.
      (or (-> response :body :auth api-util/kebabify-keys)
          (-> response :body :wrap_info api-util/kebabify-keys)
          (throw (ex-info "No auth or wrap-info in response body"
                          {:body (:body response)})))))


  (lookup-token
    [this]
    (-> (api-util/api-request this :get "auth/token/lookup-self" {})
        (get-in [:body :data])
        (api-util/kebabify-keys)))


  (lookup-token
    [this token]
    (-> (api-util/api-request
          this :post "auth/token/lookup"
          {:form-params {:token token}
           :content-type :json})
        (get-in [:body :data])
        (api-util/kebabify-keys)))


  (renew-token
    [this]
    (let [response (api-util/api-request this :post "auth/token/renew-self" {})
          auth-info (lease/auth-lease (:auth (api-util/clean-body response)))]
      (when-not (:client-token auth-info)
        (throw (ex-info (str "No client token returned from token renewal response: "
                             (:status response) " " (:reason-phrase response))
                        {:body (:body response)})))
      (reset! auth auth-info)
      auth-info))


  (renew-token
    [this token]
    (-> (api-util/api-request
          this :post "auth/token/renew"
          {:form-params {:token token}
           :content-type :json})
        (api-util/clean-body)
        (:auth)))


  (revoke-token!
    [this]
    (when-let [token (:client-token @auth)]
      (.revoke-token! this token)))


  (revoke-token!
    [this token]
    (let [response (api-util/api-request
                     this :post "auth/token/revoke"
                     {:form-params {:token token}
                      :content-type :json})]
      (= 204 (:status response))))


  (lookup-accessor
    [this token-accessor]
    (-> (api-util/api-request
          this :post "auth/token/lookup-accessor"
          {:form-params {:accessor token-accessor}
           :content-type :json})
        (get-in [:body :data])
        (api-util/kebabify-keys)))


  (revoke-accessor!
    [this token-accessor]
    (let [response (api-util/api-request
                     this :post "auth/token/revoke-accessor"
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
          response (api-util/api-request
                     this :put "sys/renew"
                     {:form-params {:lease_id lease-id}
                      :content-type :json})
          info (api-util/clean-body response)]
      ; If the lease looks renewable but the lease-duration is shorter than the
      ; existing lease, we're up against the max-ttl and the lease should not
      ; be considered renewable.
      (lease/update!
        leases
        (if (and (lease/renewable? info)
                 (< (:lease-duration info)
                    (:lease-duration current)))
          (assoc info :renewable false)
          info))))


  (revoke-lease!
    [this lease-id]
    (log/debug "Revoking lease" lease-id)
    (let [response (api-util/api-request this :put (str "sys/revoke/" lease-id) {})]
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


  vault/SecretEngine


  (list-secrets
    [this path]
    (let [response (api-util/api-request this :get path {:query-params {:list true}})
          data (get-in response [:body :data :keys])]
      (log/debugf "List %s (%d results)" path (count data))
      data))


  (read-secret
    [this path opts merge-req]
    (or (when-let [lease (and (not (:force-read opts))
                              (lease/lookup leases path))]
          (when-not (lease/expired? lease)
            (:data lease)))
        (api-util/supports-not-found
          opts
          (let [response (api-util/api-request this :get path merge-req)
                info (assoc (api-util/clean-body response)
                            :path path
                            :renew (:renew opts)
                            :rotate (:rotate opts))]

            (log/debugf "Read %s (valid for %d seconds)"
                        path (:lease-duration info))
            (lease/update! leases info)

            (:data info)))))


  (read-secret
    [this path opts]
    (.read-secret this path opts {}))


  (write-secret!
    [this path data]
    (let [response (api-util/api-request
                     this :post path
                     {:form-params data
                      :content-type :json})]
      (log/debug "Vault client wrote to" path)
      (lease/remove-path! leases path)
      (case (int (:status response -1))
        204 true
        200 (:body response)
        false)))


  (delete-secret!
    [this path]
    (let [response (api-util/api-request this :delete path {})]
      (log/debug "Vault client deleted resources at" path)
      (lease/remove-path! leases path)
      (= 204 (:status response))))


  vault/WrappingClient

  (wrap!
    [this data ttl]
    (-> (api-util/api-request
          this :post "sys/wrapping/wrap"
          {:headers {"X-Vault-Wrap-TTL" ttl}
           :form-params data
           :content-type :json})
        (get-in [:body :wrap_info])
        (api-util/kebabify-keys)))


  (unwrap!
    [this wrap-token]
    (let [response (api-util/unwrap-secret this wrap-token)]
      (or (-> response :body :auth api-util/kebabify-keys)
          (-> response :body :data)
          (throw (ex-info "No auth info or data in response body"
                          {:body (:body response)}))))))


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
