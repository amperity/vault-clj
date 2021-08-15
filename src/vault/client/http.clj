(ns vault.client.http
  "Vault HTTP client and core functions."
  (:require
    [clojure.string :as str]
    [clojure.tools.logging :as log]
    [com.stuartsierra.component :as component]
    [vault.client :as vault]
    #_[vault.lease :as lease]))


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
    (assoc opts
           :api-url api-url
           :auth (atom nil)
           #_#_:leases (lease/new-store))))


(defmethod vault/new-client "http"
  [location]
  (http-client location))


(defmethod vault/new-client "https"
  [location]
  (http-client location))
