(ns vault-clj.core
  "Protocol for interacting with Vault to fetch secrets using the HTTP API. This
  client is focused on the app-id authentication scheme."
  (:require
    [clj-http.client :as http]
    [clojure.string :as str]
    [clojure.tools.logging :as log]))


;; ## Client Protocol

(defprotocol Client
  "Protocol for fetching secrets from Vault."

  (authenticate!
    [client app-id user-id]
    "Updates the client's internal state by authenticating with the given
    credentials.")

  (read-secret
    [client path]
    "Reads a secret from a specific path."))



;; ## HTTP API Client

(defrecord HTTPClient
  [api-url token]

  Client

  (authenticate!
    [this app-id user-id]
    (let [response (http/post (str api-url "/v1/auth/app-id/login")
                     {:form-params {:app_id app-id, :user_id user-id}
                      :content-type :json
                      :accept :json
                      :as :json})]
      (when-let [client-token (get-in response [:body :auth :client_token])]
        (log/infof "Successfully authenticated to Vault app-id %s for policies: %s"
                   app-id (str/join ", " (get-in response [:body :auth :policies])))
        (reset! token client-token))
      this))


  (read-secret
    [this path]
    (when-not (string? path)
      (throw (IllegalArgumentException.
               (str "Secret path must be a string, got: " (pr-str path)))))
    (when-not @token
      (throw (IllegalStateException.
               (str "Cannot read path " path " with unauthenticated client."))))
    (let [response (http/get (str api-url "/v1/" path)
                     {:headers {"X-Vault-Token" @token}
                      :accept :json
                      :as :json})]
      (log/debugf "Leased %s for %d seconds"
                  (get-in response [:body :lease_id])
                  (get-in response [:body :lease_duration]))
      (get-in response [:body :data]))))


;; Remove automatic constructors.
(ns-unmap *ns* '->HTTPClient)
(ns-unmap *ns* 'map->HTTPClient)


(defn http-client
  "Constructs a new HTTP Vault client."
  [api-url]
  (when-not (string? api-url)
    (throw (IllegalArgumentException.
             (str "Vault api-url must be a string, got: " (pr-str api-url)))))
  (HTTPClient. api-url (atom nil)))
