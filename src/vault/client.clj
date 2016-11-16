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
    boolean indicating whether the write was successful.")

  (delete-secret!
    [client path]
    "Removes secret data from a path. Returns a boolean indicating whether the
    deletion was successful.")

  (create-token!
    [client] [client wrap-ttl]
    "Creates a new token.  Non-nil wrap-ttl values will be passed to the
     X-Vault-Wrap-TTL header.  Returns the body of the successful repsonse or nil.")

  (unwrap!
    [client wrap-token]
    "Returns the original response inside the given wrapping token."))



;; ## Mock Memory Client

(defrecord MemoryClient
  [memory]

  Client

  (authenticate!
    [this auth-type credentials]
    this)

  (list-secrets
    [this path]
    (filter #(str/starts-with? % (str path)) (keys @memory)))

  (read-secret
    [this path]
    (or (get @memory path)
        (throw (ex-info (str "No such secret: " path) {:secret path}))))

  (write-secret!
    [this path data]
    (swap! memory assoc path data)
    true)

  (delete-secret!
    [this path]
    (swap! memory dissoc path)
    true)

  (create-token! [this] (create-token! this nil))

  (create-token!
    [this wrap-ttl]
    {:request_id "",
     :lease_id "",
     :renewable false,
     :lease_duration 0,
     :data nil,
     :wrap_info
     {:token "87e771a4-73a5-8eda-fe59-a5cc5d41a6d2",
      :ttl 300,
      :creation_time "2016-11-15T16:09:05.570104718-08:00",
      :wrapped_accessor "2bde7146-78a2-541c-a073-59d6f1535186"},
     :warnings nil,
     :auth nil})

  (unwrap!
    [this wrap-token]
    {:request_id "21a1a583-a03b-f465-27a9-0461979c7aa7",
     :lease_id "",
     :renewable false,
     :lease_duration 0,
     :data nil,
     :wrap_info nil,
     :warnings nil,
     :auth
     {:client_token "2a52fd73-5ec2-6690-0caa-ef7733c3f6cf",
      :accessor "2bde7146-78a2-541c-a073-59d6f1535186",
      :policies ["root"],
      :metadata nil,
      :lease_duration 0,
      :renewable false}}))


;; Remove automatic constructors.
(ns-unmap *ns* '->MemoryClient)
(ns-unmap *ns* 'map->MemoryClient)


(defn memory-client
  "Constructs a new in-memory Vault mock client."
  ([]
   (memory-client {}))
  ([initial]
   (MemoryClient. (atom initial :validator map?))))



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

(defn- build-headers
  [token & [wrap-ttl]]
  (merge {"X-Vault-Token" token}
         (when wrap-ttl
           {"X-Vault-Wrap-TTL" wrap-ttl})))

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
    (let [response (api-request :get (str api-url "/v1/" path)
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
    (let [info (or (cache/lookup cache path)
                   (let [response (api-request :get (str api-url "/v1/" path)
                                    {:headers {"X-Vault-Token" @token}
                                     :accept :json
                                     :as :json})]
                     (log/debugf "Read %s (valid for %d seconds)"
                                 path (get-in response [:body :lease_duration]))
                     (cache/store! cache path (:body response))))]
      (when-not info
        (log/warn "No value found for secret" path))
      (:data info)))


  (write-secret!
    [this path data]
    (check-path! path)
    (check-auth! token)
    (let [response (api-request :post (str api-url "/v1/" path)
                     {:headers {"X-Vault-Token" @token}
                      :form-params data
                      :content-type :json
                      :accept :json
                      :as :json})]
      (log/debug "Wrote secret" path)
      (cache/invalidate! cache path)
      (= (:status response) 204)))


  (delete-secret!
    [this path]
    (check-path! path)
    (check-auth! token)
    (let [response (api-request :delete (str api-url "/v1/" path)
                     {:headers {"X-Vault-Token" @token}
                      :accept :json
                      :as :json})]
      (log/debug "Deleted secret" path)
      (cache/invalidate! cache path)
      (= (:status response) 204)))

  (create-token! [this] (create-token! this nil))

  (create-token!
    [this wrap-ttl]
    (check-auth! token)
    (let [response (api-request :post (str api-url "/v1/auth/token/create")
                                {:headers (build-headers @token wrap-ttl)
                                 :accept :json
                                 :as :json})]
      (log/debug "Created token" (when wrap-ttl "with X-Vault-Wrap-TTL" wrap-ttl))
      (when (= (:status response) 200)
        (:body response))))

  (unwrap!
    [this wrap-token]
    (let [response (api-request :post (str api-url "/v1/sys/wrapping/unwrap")
                                {:headers (build-headers wrap-token)
                                 :accept :json
                                 :as :json})]
      (log/debug "Unwrapping response for" wrap-token)
      (when (= (:status response) 200)
        (:body response)))))


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
