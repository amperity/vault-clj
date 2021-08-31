(ns vault.client.http
  "Vault HTTP client and core functions."
  (:require
    [clojure.data.json :as json]
    [clojure.string :as str]
    [clojure.tools.logging :as log]
    [com.stuartsierra.component :as component]
    [org.httpkit.client :as http]
    [vault.client :as vault]
    [vault.client.response :as resp]
    [vault.client.util :as u]
    #_[vault.lease :as lease]))


;; ## API Functions

(defn- prepare-request
  "Produce a map of options to pass to the HTTP client from the provided
  method, API path, and other request parameters."
  [client method path params]
  (let [token (:client-token @(:auth client))]
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


(defn- parse-body
  "Parse a response body, rewriting the keywords from snake_case to kebab-case. The
  `:data` key (if any) is preserved as-is."
  [body]
  (when body
    (let [parsed (json/read-str body :key-fn keyword)
          data (:data parsed)]
      (-> parsed
          (dissoc :data)
          (u/walk-keys u/kebab-keyword)
          (cond->
            data
            (assoc :data data))))))


(defn ^:no-doc call-api
  "Make an HTTP call to the Vault API, using the client's response handler to
  prepare and return the response state."
  [client method path params]
  (let [handler (:response-handler client)
        request (prepare-request client method path params)
        response (resp/create handler)]
    ;; TODO: handle redirects
    (http/request
      request
      (fn callback
        [{:keys [status headers body error]}]
        (if error
          ;; TODO: shape exception
          (resp/on-error! handler response error)
          ;; Parse body data and attach response metadata.
          (let [data (vary-meta (parse-body body)
                                assoc
                                ::status status
                                ::headers headers)]
            (resp/on-success! handler response data)))))
    (resp/return handler response)))


;; ## Lease Timer

(defn- timer-loop
  "Constructs a new runnable looping function which performs the timer logic
  every `period` seconds. If the `jitter` property is set, the sleep cycle will
  vary randomly by up to `jitter` seconds in length (meaning in the range
  `[p, p+j)`).

  The loop can be terminated by interrupting the thread."
  ^Runnable
  [handler period jitter]
  (fn []
    (try
      (while (not (Thread/interrupted))
        (Thread/sleep (* (+ period (rand-int jitter)) 1000))
        (try
          (handler)
          (catch InterruptedException ex
            (throw ex))
          (catch Exception ex
            (log/error ex "Error while running timer handler!"))))
      (catch InterruptedException _
        nil))))


(defn- start-timer!
  "Constructs and starts a new timer thread to call the given handler function.
  The returned thread will be in daemon mode."
  [label handler period jitter]
  (log/infof "Starting timer thread %s with period of %d seconds (~%d jitter)"
             label period jitter)
  (doto (Thread. (timer-loop handler period jitter) (str label))
    (.setDaemon true)
    (.start)))


(defn- stop-timer!
  "Stops a running timer thread cleanly if possible."
  [^Thread thread]
  (log/info "Interrupting timer thread" (.getName thread))
  (.interrupt thread)
  (.join thread 1000))


;; ## HTTP Client

;; - `address`
;;   The base URL for the Vault API endpoint.
;; - `response-handler`
;;   Handler type to use for API responses.
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
  [address response-handler http-opts auth leases lease-timer]

  component/Lifecycle

  (start
    [this]
    (if lease-timer
      ;; Already running
      this
      ;; Start lease heartbeat thread.
      (let [window (:lease-renewal-window this 300)
            period (:lease-check-period   this  60)
            jitter (:lease-check-jitter   this  10)
            thread (start-timer!
                     "vault-lease-timer"
                     (constantly true) #_
                     #(lease/maintain-leases! this window)
                     period
                     jitter)]
        (assoc this :lease-timer thread))))


  (stop
    [this]
    (if lease-timer
      (do
        ;; Stop lease timer thread.
        (stop-timer! lease-timer)
        ;; Revoke all outstanding leases.
        #_
        (when-let [outstanding (and (:revoke-on-stop? this)
                                    (seq (filter lease/leased? (vault/list-leases this))))]
          (log/infof "Revoking %d outstanding secret leases" (count outstanding))
          (doseq [secret outstanding]
            (try
              (vault/revoke-lease! this (:lease-id secret))
              (catch Exception ex
                (log/error ex "Failed to revoke lease" (:lease-id secret))))))
        (assoc this :lease-timer nil))
      ;; Already stopped.
      this))


  vault/Client

  ,,,)


;; ## Constructors

;; Privatize automatic constructors.
(alter-meta! #'->HTTPClient assoc :private true)
(alter-meta! #'map->HTTPClient assoc :private true)


(defn http-client
  "Constructs a new HTTP Vault client.

  Client behavior may be controlled with the options:

  - `:response-handler`
    A custom handler to control how responses are returned to clients. Defaults
    to the `sync-handler`.
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
  [address & {:as opts}]
  (when-not (and (string? address) (str/starts-with? address "http"))
    (throw (IllegalArgumentException.
             (str "Vault API address must be a string starting with 'http', got: "
                  (pr-str address)))))
  (map->HTTPClient
    (merge {:response-handler resp/sync-handler}
           opts
           {:address address
            :auth (atom nil)
            #_#_:leases (lease/new-store)})))


(defmethod vault/new-client "http"
  [address]
  (http-client address))


(defmethod vault/new-client "https"
  [address]
  (http-client address))
