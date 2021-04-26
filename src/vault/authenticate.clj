(ns vault.authenticate
  "Handles logic relating to the authentication of a Vault client"
  (:require
    [clojure.string :as str]
    [clojure.tools.logging :as log]
    [vault.client.api-util :as api-util]
    [vault.lease :as lease]))


(defn ^:no-doc api-auth!
  "Validate the response from a vault auth call, update auth-ref with additional
  tracking state like lease metadata."
  [claim auth-ref response]
  (let [auth-info (lease/auth-lease (:auth (api-util/clean-body response)))]
    (when-not (:client-token auth-info)
      (throw (ex-info (str "No client token returned from non-error API response: "
                           (:status response) " " (:reason-phrase response))
                      {:body (:body response)})))
    (log/infof "Successfully authenticated to Vault as %s for policies: %s"
               claim (str/join ", " (:policies auth-info)))
    (reset! auth-ref auth-info)))


(defmulti authenticate*
  "Authenticate the client with vault using the given auth-type and credentials."
  (fn dispatch
    [_client auth-type _credentials]
    auth-type))


(defmethod authenticate* :default
  [_ auth-type _]
  (throw (ex-info (str "Unsupported auth-type " (pr-str auth-type))
                  {:auth-type auth-type})))


(defmethod authenticate* :token
  [client _ token]
  (when-not (string? token)
    (throw (IllegalArgumentException. "Token credential must be a string")))
  (reset! (:auth client) {:client-token (str/trim token)}))


(defmethod authenticate* :wrap-token
  [client _ credentials]
  (api-auth!
    "wrapped token"
    (:auth client)
    (api-util/unwrap-secret client credentials)))


(defmethod authenticate* :userpass
  [client _ credentials]
  (let [{:keys [username password]} credentials]
    (api-auth!
      (str "user " username)
      (:auth client)
      (api-util/do-api-request
        :post (str (:api-url client) "/v1/auth/userpass/" (:auth-mount-point client) "login/" username)
        (merge
          (:http-opts client)
          {:form-params {:password password}
           :content-type :json
           :accept :json
           :as :json})))))


(defmethod authenticate* :app-id
  [client _ credentials]
  (let [{:keys [app user]} credentials]
    (api-auth!
      (str "app-id " app)
      (:auth client)
      (api-util/do-api-request
        :post (str (:api-url client) "/v1/auth/app-id/" (:auth-mount-point client) "login")
        (merge
          (:http-opts client)
          {:form-params {:app_id app, :user_id user}
           :content-type :json
           :accept :json
           :as :json})))))


(defmethod authenticate* :app-role
  [client _ credentials]
  (let [{:keys [role-id secret-id]} credentials]
    (api-auth!
      (str "role-id sha256:" (api-util/sha-256 role-id))
      (:auth client)
      (api-util/do-api-request
        :post (str (:api-url client) "/v1/auth/approle/" (:auth-mount-point client) "login")
        (merge
          (:http-opts client)
          {:form-params {:role_id role-id, :secret_id secret-id}
           :content-type :json
           :accept :json
           :as :json})))))


(defmethod authenticate* :ldap
  [client _ credentials]
  (let [{:keys [username password]} credentials]
    (api-auth!
      (str "LDAP user " username)
      (:auth client)
      (api-util/do-api-request
        :post (str (:api-url client) "/v1/auth/ldap/" (:auth-mount-point client) "login/" username)
        (merge
          (:http-opts client)
          {:form-params {:password password}
           :content-type :json
           :accept :json
           :as :json})))))


(defmethod authenticate* :k8s
  [client _ credentials]
  (let [{:keys [api-path jwt role]} credentials
        api-path (or api-path (str "/v1/auth/kubernetes/" (:auth-mount-point client) "login"))]
    (when-not jwt
      (throw (IllegalArgumentException. "Kubernetes auth credentials must include :jwt")))
    (when-not role
      (throw (IllegalArgumentException. "Kubernetes auth credentials must include :role")))
    (api-auth!
      (str "Kubernetes auth role=" role)
      (:auth client)
      (api-util/do-api-request
        :post (str (:api-url client) api-path)
        (merge
          (:http-opts client)
          {:form-params {:jwt jwt :role role}
           :content-type :json
           :accept :json
           :as :json})))))


(defmethod authenticate* :aws-iam
  [client _ credentials]
  (let [{:keys [api-path http-request-method request-url request-body request-headers
                role]} credentials
        api-path (or api-path (str "/v1/auth/aws/" (:auth-mount-point client) "login"))]
    (when-not http-request-method
      (throw (IllegalArgumentException. "AWS auth credentials must include :http-request-method")))
    (when-not request-url
      (throw (IllegalArgumentException. "AWS auth credentials must include :request-url")))
    (when-not request-body
      (throw (IllegalArgumentException. "AWS auth credentials must include :request-body")))
    (when-not request-headers
      (throw (IllegalArgumentException. "AWS auth credentials must include :request-headers")))
    (when-not role
      (throw (IllegalArgumentException. "AWS auth credentials must include :role")))
    (api-auth!
      (str "AWS auth role=" role)
      (:auth client)
      (api-util/do-api-request
        :post (str (:api-url client) api-path)
        (merge
          (:http-opts client)
          {:form-params {:iam_http_request_method http-request-method
                         :iam_request_url request-url
                         :iam_request_body request-body
                         :iam_request_headers request-headers
                         :role role}
           :content-type :json
           :accept :json
           :as :json})))))
