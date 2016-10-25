(ns vault.client
  "Protocol for interacting with Vault to fetch secrets using the HTTP API. This
  client is focused on the app-id authentication scheme."
  (:require
    [clj-http.client :as http]
    [clojure.string :as str]
    [clojure.tools.logging :as log]
    [vault.cache :as cache]))


;; ## Client Protocol

(defprotocol Client
  "Protocol for fetching secrets from Vault."

  (authenticate!
    [client auth-type credentials]
    "Updates the client's internal state by authenticating with the given
    credentials. Possible arguments:

    - :token \"...\"
    - :userpass {:username \"user\", :password \"hunter2\"}
    - :app-id {:app \"lambda_ci\", :user \"...\"}")

  (list-secrets
    [client path]
    "List the secrets located under a path.")

  (read-secret
    [client path]
    "Reads a secret from a path. Returns the full map of stored secret data.")

  (write-secret!
    [client path data]
    "Writes secret data to a path. `data` should be a map. Returns a
    boolean indicating whether the write was successful."))



;; ## HTTP API Client

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
        response (http/post (str api-url "/v1/auth/userpass/login/" username)
                   {:form-params {:password password}
                    :content-type :json
                    :accept :json
                    :as :json})]
    (when-let [client-token (get-in response [:body :auth :client_token])]
      (log/infof "Successfully authenticated to Vault as %s for policies: %s"
                 username (str/join ", " (get-in response [:body :auth :policies])))
      (reset! token-ref client-token))))


(defn- authenticate-app!
  "Updates the token ref by making a request to authenticate with an app-id and
  secret user-id."
  [api-url token-ref credentials]
  (let [{:keys [app user]} credentials
        response (http/post (str api-url "/v1/auth/app-id/login")
                   {:form-params {:app_id app, :user_id user}
                    :content-type :json
                    :accept :json
                    :as :json})]
    (when-let [client-token (get-in response [:body :auth :client_token])]
      (log/infof "Successfully authenticated to Vault app-id %s for policies: %s"
                 app (str/join ", " (get-in response [:body :auth :policies])))
      (reset! token-ref client-token))))


(defrecord HTTPClient
  [api-url token cache]

  Client

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


  (list-secrets
    [this path]
    (check-path! path)
    (check-auth! token)
    (let [response (http/get (str api-url "/v1/" path)
                     {:query-params {:list true}
                      :headers {"X-Vault-Token" @token}
                      :accept :json
                      :as :json})
          data (get-in response [:body :data :keys])]
      (log/debugf "List %s (%d results)" path (count data))
      data))


  (read-secret
    [this path]
    (check-path! path)
    (check-auth! token)
    (let [data (or (cache/lookup cache path)
                   (let [response (http/get (str api-url "/v1/" path)
                                    {:headers {"X-Vault-Token" @token}
                                     :accept :json
                                     :as :json})]
                     (log/debugf "Read %s (valid for %d seconds)"
                                 path (get-in response [:body :lease_duration]))
                     (cache/store! cache (:body response))))]
      (when-not data
        (log/warn "No value found for secret" path))
      data))


  (write-secret!
    [this path data]
    (check-path! path)
    (check-auth! token)
    (let [response (http/post (str api-url "/v1/" path)
                     {:headers {"X-Vault-Token" @token}
                      :form-params data
                      :content-type :json
                      :accept :json
                      :as :json})]
      (log/debug "Wrote secret" path)
      (cache/invalidate! cache path)
      (= (:status response) 204))))


;; Remove automatic constructors.
(ns-unmap *ns* '->HTTPClient)
(ns-unmap *ns* 'map->HTTPClient)


(defn http-client
  "Constructs a new HTTP Vault client."
  [api-url]
  (when-not (string? api-url)
    (throw (IllegalArgumentException.
             (str "Vault api-url must be a string, got: " (pr-str api-url)))))
  (HTTPClient. api-url (atom nil) (cache/new-cache)))
