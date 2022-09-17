(ns vault.client.http
  "Vault HTTP client and core functions."
  (:require
    [clojure.data.json :as json]
    [clojure.string :as str]
    [org.httpkit.client :as http]
    [vault.auth :as auth]
    [vault.client :as vault]
    [vault.client.handler :as h]
    [vault.lease :as lease]
    [vault.util :as u])
  (:import
    (java.time
      Instant
      ZonedDateTime)))


;; ## API Functions

(defn- prepare-request
  "Produce a map of options to pass to the HTTP client from the provided
  method, API path, and other request parameters."
  [client method path params]
  (let [token (::auth/client-token @(:auth client))]
    (->
      (:http-opts client)
      (assoc :accept :json)
      (merge params)
      (assoc :method method
             :url (str (:address client) "/v1/" path))
      (cond->
        token
        (assoc-in [:headers "X-Vault-Token"] token)

        (and (= :json (:content-type params))
             (:body params))
        (update :body json/write-str)))))


(defn- default-handle-response
  "Coerce the keys in a response from snake_case strings to kebab-case
  keywords. The `:data` key (if any) is keywordized but the case is preserved."
  [body]
  (let [raw-body (get body "data")]
    (-> body
        (dissoc "data")
        (u/kebabify-keys)
        (cond->
          raw-body
          (assoc :data (u/walk-keys raw-body keyword))))))


(defn- form-success
  "Handle a successful response from the API. Returns the data which should be
  yielded by the response."
  [status headers body handle-response]
  (let [parsed (when-not (str/blank? body)
                 (json/read-str body))
        request-id (get parsed "request_id")
        warnings (get parsed "warnings")]
    (some->
      parsed
      (handle-response)
      (vary-meta assoc
                 ::vault/status status
                 ::vault/headers headers)
      (cond->
        request-id
        (vary-meta assoc ::vault/request-id request-id)

        (seq warnings)
        (vary-meta assoc ::vault/warnings warnings)))))


(defn- form-failure
  "Handle a failure response from the API. Returns the exception which should
  be yielded by the response."
  [status headers body]
  (let [parsed (when-not (str/blank? body)
                 (json/read-str body))
        request-id (get parsed "request_id")
        warnings (get parsed "warnings")
        errors (get parsed "errors")]
    (->
      {::vault/status status
       ::vault/headers headers}
      (cond->
        request-id
        (assoc ::vault/request-id request-id)

        (seq warnings)
        (assoc ::vault/warnings warnings)

        (seq errors)
        (assoc ::vault/errors errors))
      (as-> data
        (ex-info (if (seq errors)
                   (str "Vault API errors: " (str/join ", " errors))
                   (str "Vault HTTP error: "
                        (case (int status)
                          400 "bad request"
                          404 "not found"
                          status)))
                 data)))))


(defn ^:no-doc call-api
  "Make an HTTP call to the Vault API, using the client's request handler to
  prepare and initiate the call."
  [client method path params]
  (when-not client
    (throw (IllegalArgumentException. "Cannot make API call on nil client")))
  (when-not (keyword? method)
    (throw (IllegalArgumentException. "Cannot make API call without keyword method")))
  (when (str/blank? path)
    (throw (IllegalArgumentException. "Cannot make API call on blank path")))
  (let [handler (:handler client)
        request (prepare-request client method path params)]
    (letfn [(make-request
              [extra]
              (http/request
                (merge request extra)
                callback))

            (callback
              [{:keys [opts status headers body error]}]
              (let [{::keys [state redirects]} opts]
                (try
                  (if error
                    ;; TODO: shape exception?
                    (h/on-error! handler state error)
                    ;; Handle response from the API based on status code.
                    (cond
                      ;; Successful response, parse body and return result.
                      (<= 200 status 299)
                      (let [handle-response (:handle-response params default-handle-response)
                            data (form-success status headers body handle-response)]
                        (h/on-success! handler state data))

                      ;; Request was redirected by the server, which could mean
                      ;; we called a standby node on accident.
                      (or (= 303 status) (= 307 status))
                      (let [location (get headers "Location")]
                        (cond
                          (nil? location)
                          (h/on-error!
                            handler
                            state
                            (ex-info (str "Vault API responded with " status
                                          " redirect without Location header")
                                     {::vault/status status
                                      ::vault/headers headers}))

                          (< 2 redirects)
                          (h/on-error!
                            handler
                            state
                            (ex-info (str "Aborting Vault API request after " redirects
                                          " redirects")
                                     {::vault/status status
                                      ::vault/headers headers}))

                          :else
                          (make-request
                            {::state state
                             ::redirects (inc redirects)
                             :url location})))

                      ;; Otherwise, this was a failure response.
                      :else
                      (let [handle-error (:handle-error params identity)
                            result (handle-error (form-failure status headers body))]
                        (if (instance? Throwable result)
                          (h/on-error! handler state result)
                          (h/on-success! handler state result)))))
                  (catch Exception ex
                    ;; Unhandled exception while processing response.
                    (h/on-error! handler state ex)))))]
      ;; Kick off the request.
      (h/call
        handler
        (merge {::vault/method method
                ::vault/path path}
               (when-let [query (:query-params params)]
                 {::vault/query query}))
        (fn call
          [state]
          (make-request {::state state
                         ::redirects 0}))))))


(defn ^:no-doc cached-response
  "Return a response without calling the API. Uses the client's request handler
  to prepare and return the cached secret data."
  [client data]
  (let [handler (:handler client)]
    (h/call
      handler
      {::vault/cached? true}
      (fn cached
        [state]
        (h/on-success! handler state data)))))


(defn ^:no-doc lease-info
  "Parse lease information out of an HTTP response body."
  [body]
  (merge
    (when-let [lease-id (get body "lease_id")]
      {::lease/id lease-id})
    (when-let [lease-duration (get body "lease_duration")]
      {::lease/duration lease-duration
       ::lease/expires-at (.plusSeconds (u/now) lease-duration)})
    (when-some [renewable (get body "renewable")]
      {::lease/renewable? renewable})))


(defn ^:no-doc shape-auth
  "Coerce a map of non-namespaced token information from `create-token!` or
  `lookup-token` into the authentication map shape."
  [auth]
  (let [lease-duration (or (::auth/lease-duration auth)
                           (:lease-duration auth))
        created-at (or (::auth/created-at auth)
                       (some-> (:creation-time auth)
                               (Instant/ofEpochSecond)))
        expires-at (or (::auth/expires-at auth)
                       (if-let [expire-time (:expire-time auth)]
                         (try
                           (.toInstant (ZonedDateTime/parse expire-time))
                           (catch Exception _
                             nil))
                         (when lease-duration
                           (let [start (or created-at (u/now))]
                             (.plusSeconds start lease-duration)))))]
    (into {}
          (filter (comp some? val))
          {::auth/accessor (or (::auth/accessor auth)
                               (:accessor auth))
           ::auth/client-token (or (::auth/client-token auth)
                                   (:client-token auth))
           ::auth/display-name (or (::auth/display-name auth)
                                   (:display-name auth))
           ::auth/lease-duration lease-duration
           ::auth/policies (or (::auth/policies auth)
                               (not-empty (set (or (:token-policies auth)
                                                   (:policies auth)))))
           ::auth/orphan? (if-some [orphan? (::auth/orphan? auth)]
                            orphan?
                            (:orphan auth))
           ::auth/renewable? (if-some [renewable? (::auth/renewable? auth)]
                               renewable?
                               (:renewable auth))
           ::auth/created-at created-at
           ::auth/expires-at expires-at})))


;; ## HTTP Client

;; - `address`
;;   The base URL for the Vault API endpoint.
;; - `handler`
;;   Request handler to use for API calls.
;; - `http-opts`
;;   Extra options to pass to `clj-http` requests.
;; - `auth`
;;   Atom containing the authentication lease information, including the
;;   client token.
;; - `leases`
;;   Local in-memory storage of secret leases.
(defrecord HTTPClient
  [address handler http-opts auth leases]

  vault/Client

  (auth-info
    [_]
    @auth)


  (authenticate!
    [this auth-info]
    (let [auth-info (if (string? auth-info)
                      {::auth/client-token auth-info}
                      (shape-auth auth-info))]
      (when-not (and (map? auth-info) (::auth/client-token auth-info))
        (throw (IllegalArgumentException.
                 "Client authentication must be a map of information containing a client-token.")))
      (reset! auth auth-info)
      this)))


;; ## Constructors

;; Privatize automatic constructors.
(alter-meta! #'->HTTPClient assoc :private true)
(alter-meta! #'map->HTTPClient assoc :private true)


(defn http-client
  "Constructs a new HTTP Vault client.

  Client behavior may be controlled with the options:

  - `:handler`
    A custom handler to control how requests and responses are handled. Defaults
    to the `sync-handler`.
  - `:http-opts`
    Additional options to pass to `http` requests."
  [address & {:as opts}]
  (when-not (and (string? address)
                 (or (str/starts-with? address "http://")
                     (str/starts-with? address "https://")))
    (throw (IllegalArgumentException.
             (str "Vault API address must be a URL with scheme 'http' or 'https': "
                  (pr-str address)))))
  (map->HTTPClient
    (merge {:handler h/sync-handler}
           opts
           {:address address
            :auth (auth/new-state)
            :leases (lease/new-store)})))
