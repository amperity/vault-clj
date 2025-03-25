(ns vault.client.http
  "Vault HTTP client and core functions."
  (:require
    [clojure.data.json :as json]
    [clojure.string :as str]
    [org.httpkit.client :as http]
    [vault.auth :as auth]
    [vault.client.flow :as f]
    [vault.client.proto :as proto]
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
  (let [token (::auth/token (auth/current (:auth client)))]
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
          (assoc :data (u/keywordize-keys raw-body))))))


(defn- form-success
  "Handle a successful response from the API. Returns a pair with the collected
  response information and the parsed result data."
  [status headers body info handle-response]
  (let [parsed (when-not (str/blank? body)
                 (json/read-str body))
        request-id (get parsed "request_id")
        warnings (get parsed "warnings")
        res-info (->
                   info
                   (assoc :vault.client/status status
                          :vault.client/headers headers)
                   (cond->
                     request-id
                     (assoc :vault.client/request-id request-id)

                     (seq warnings)
                     (assoc :vault.client/warnings warnings)))
        data (some->
               parsed
               (handle-response)
               (vary-meta merge res-info))]
    [res-info data]))


(defn- form-failure
  "Handle a failure response from the API. Returns a pair with the collected
  response information and the exception which should be yielded."
  [path status headers info body]
  (let [parsed (when-not (str/blank? body)
                 (json/read-str body))
        request-id (get parsed "request_id")
        warnings (get parsed "warnings")
        errors (get parsed "errors")
        res-info (->
                   info
                   (assoc :vault.client/status status
                          :vault.client/headers headers)
                   (cond->
                     request-id
                     (assoc :vault.client/request-id request-id)

                     (seq warnings)
                     (assoc :vault.client/warnings warnings)

                     (seq errors)
                     (assoc :vault.client/errors errors)))
        message (if (seq errors)
                  (format "Vault API error on %s (%s) %s"
                          path status (str/join ", " errors))
                  (format "Vault HTTP error on %s (%s)%s"
                          path status
                          (case (int status)
                            400 " bad request"
                            404 " not found"
                            "")))]
    [res-info (ex-info message res-info)]))


(defn ^:no-doc call-api
  "Make an HTTP call to the Vault API, using the client's flow handler to
  prepare and initiate the call."
  [client api-label method path params]
  (when-not client
    (throw (IllegalArgumentException. "Cannot make API call on nil client")))
  (when-not (keyword? api-label)
    (throw (IllegalArgumentException. "Cannot make API call without keyword label")))
  (when-not (keyword? method)
    (throw (IllegalArgumentException. "Cannot make API call without keyword method")))
  (when (str/blank? path)
    (throw (IllegalArgumentException. "Cannot make API call on blank path")))
  (let [handler (:flow client)
        request (prepare-request client method path params)
        info (merge (:info params)
                    {:vault.client/api api-label
                     :vault.client/method method
                     :vault.client/path path
                     :vault.client/address (:address client)}
                    (when-let [query (not-empty (:query-params params))]
                      {:vault.client/query query}))]
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
                    ;; Call error handler.
                    (f/on-error! handler state info error)
                    ;; Handle response from the API based on status code.
                    (cond
                      ;; Successful response, parse body and return result.
                      (<= 200 status 299)
                      (let [handle-response (:handle-response params default-handle-response)
                            [res-info data] (form-success status headers body info handle-response)]
                        (when-let [on-success (:on-success params)]
                          (on-success data))
                        (f/on-success! handler state res-info data))

                      ;; Request was redirected by the server, which could mean
                      ;; we called a standby node on accident.
                      (or (= 303 status) (= 307 status))
                      (let [location (get headers "Location")
                            res-info (assoc info
                                            :vault.client/status status
                                            :vault.client/headers headers)]
                        (cond
                          (nil? location)
                          (f/on-error!
                            handler state res-info
                            (ex-info (str "Vault API responded with " status
                                          " redirect without Location header")
                                     res-info))

                          (< 2 redirects)
                          (f/on-error!
                            handler state res-info
                            (ex-info (str "Aborting Vault API request after " redirects
                                          " redirects")
                                     res-info))

                          :else
                          (make-request
                            {::state state
                             ::redirects (inc redirects)
                             :url location})))

                      ;; Otherwise, this was a failure response.
                      :else
                      (let [handle-error (:handle-error params identity)
                            [res-info ex] (form-failure path status headers info body)
                            result (handle-error ex)]
                        (if (instance? Throwable result)
                          (f/on-error! handler state res-info result)
                          (f/on-success! handler state res-info result)))))
                  (catch Exception ex
                    ;; Unhandled exception while processing response.
                    (f/on-error! handler state info ex)))))]
      ;; Kick off the request.
      (f/call
        handler info
        (fn call
          [state]
          (make-request {::state state
                         ::redirects 0}))))))


(defn ^:no-doc cached-response
  "Return a response without calling the API. Uses the client's flow handler
  to prepare and return the cached secret data."
  [client api-label info data]
  (let [handler (:flow client)
        info (assoc info
                    :vault.client/api api-label
                    :vault.client/cached? true)
        data (vary-meta data merge info)]
    (f/call
      handler info
      (fn cached
        [state]
        (f/on-success! handler state info data)))))


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
  "Coerce a map of token information into the authentication map shape. This
  generally comes from `create-token!`, `lookup-token`, or various auth method
  login http responses."
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
                             (.plusSeconds ^Instant start lease-duration)))))]
    (into {}
          (filter (comp some? val))
          {::auth/token (or (::auth/token auth)
                            (:client-token auth))
           ::auth/accessor (or (::auth/accessor auth)
                               (:accessor auth))
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


(defn ^:no-doc not-found?
  "True if an exception represents a not-found response from the server."
  [ex]
  (let [data (ex-data ex)]
    (and (empty? (:vault.client/errors data))
         (= 404 (:vault.client/status data)))))


(defn ^:no-doc generate-rotatable-credentials!
  "Common logic for generating credentials which can be rotated in the future."
  [client api-label method path params opts]
  (if-let [data (and (not (:refresh? opts))
                     (lease/find-data client (:cache-key params)))]
    ;; Re-use cached secret.
    (cached-response client api-label (:info params) data)
    ;; No cached value available, call API.
    (call-api
      client api-label method path
      {:info (:info params)
       :handle-response
       (fn handle-response
         [body]
         (let [lease (lease-info body)
               data (-> (get body "data")
                        (u/keywordize-keys)
                        (vary-meta merge (:info params)))]
           (when lease
             (letfn [(rotate!
                       []
                       (f/call-sync
                         generate-rotatable-credentials!
                         client api-label
                         method path params
                         (assoc opts :refresh? true)))]
               (lease/put!
                 client
                 (-> lease
                     (assoc ::lease/key (:cache-key params))
                     (lease/renewable-lease opts)
                     (lease/rotatable-lease opts rotate!))
                 data)))
           (vary-meta data merge lease)))})))


;; ## HTTP Client

;; - `flow`
;;   Control flow handler.
;; - `auth`
;;   Atom containing the authentication state.
;; - `leases`
;;   Local secret lease tracker.
;; - `address`
;;   The base URL for the Vault API endpoint.
;; - `http-opts`
;;   Extra options to pass to `clj-http` requests.
(defrecord HTTPClient
  [flow auth leases address http-opts]

  Object

  (toString
    [this]
    (str "#<" (.getName (class this)) "@"
         (Integer/toHexString (System/identityHashCode this))
         " "
         (or address "---")
         ">"))


  proto/Client

  (auth-info
    [_]
    (auth/current auth))


  (authenticate!
    [this auth-info]
    (let [auth-info (if (string? auth-info)
                      {::auth/token auth-info}
                      (shape-auth auth-info))]
      (when-not (and (map? auth-info) (::auth/token auth-info))
        (throw (IllegalArgumentException.
                 "Client authentication must be a map of information containing an auth token.")))
      (auth/set! auth auth-info)
      this)))


;; Define a custom print method which doesn't expose sensitive internal state.
(defmethod print-method HTTPClient
  [client writer]
  (binding [*out* writer]
    (print (str client))))


;; ## Constructors

;; Privatize automatic constructors.
(alter-meta! #'->HTTPClient assoc :private true)
(alter-meta! #'map->HTTPClient assoc :private true)


(defn http-client
  "Create a new HTTP Vault client. The address should be an `http://` or
  `https://` URL.

  Options:

  - `:flow` ([[vault.client.flow/Handler]])

    Custom control flow handler to use with the client. Defaults to
    [[vault.client.flow/sync-handler]].

  - `:http-opts` (map)

    Additional options to pass to all HTTP requests."
  [address & {:as opts}]
  (when-not (and (string? address)
                 (or (str/starts-with? address "http://")
                     (str/starts-with? address "https://")))
    (throw (IllegalArgumentException.
             (str "Vault API address must be a URL with scheme 'http' or 'https': "
                  (pr-str address)))))
  (map->HTTPClient
    (merge {:flow f/sync-handler}
           opts
           {:address address
            :auth (auth/new-state)
            :leases (lease/new-store)})))
