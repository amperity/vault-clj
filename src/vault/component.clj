(ns vault.component
  "Support for Vault clients as components in a larger system."
  (:require
    [clojure.java.io :as io]
    [clojure.string :as str]
    [clojure.tools.logging :as log]
    [vault.auth :as auth]
    [vault.auth.token :as token]
    [vault.client.proto :as proto]
    [vault.client.handler :as h]
    [vault.client.http :as http]
    [vault.client.mock :as mock]
    [vault.lease :as lease])
  (:import
    java.net.URI))


;; ## Timer Logic

(defn- tick
  "Perform the maintenance expected during a single tick of the timer loop."
  [client]
  (auth/maintain!
    (:auth client)
    #(h/call-sync token/renew-token! client {}))
  (lease/maintain-leases!
    (:leases client)))


(defn- timer-loop
  "Constructs a new runnable looping function which performs the timer logic
  every `period` milliseconds. If the `jitter` property is set, the sleep cycle
  will vary randomly by up to `jitter` percent in length (meaning in the
  range `[p, p+(j*p)]`).

  The loop can be terminated by interrupting the thread."
  ^Runnable
  [client period jitter]
  (fn runnable
    []
    (try
      (while (not (Thread/interrupted))
        (Thread/sleep (+ period (rand-int (long (* jitter period)))))
        (try
          (tick client)
          (catch InterruptedException ex
            (throw ex))
          (catch Exception ex
            (log/error ex "Unhandled error while running timer!"))))
      (catch InterruptedException _
        nil))))


(defn- start-thread!
  "Constructs and starts a new timer thread to maintain the given client. The
  returned thread will be in daemon mode."
  [client period]
  (log/infof "Starting vault timer thread with period of %d ms" period)
  (doto (Thread. (timer-loop client period 0.10)
                 "vault-client-timer")
    (.setDaemon true)
    (.start)))


(defn- stop-thread!
  "Stops a running timer thread cleanly if possible."
  [^Thread thread]
  (when (.isAlive thread)
    (log/debug "Stopping timer thread" (.getName thread))
    (.interrupt thread)
    (.join thread 1000)))


;; ## Component Lifecycle

(defn start
  "Start the Vault component, returning an updated version with a timer
  thread."
  [client]
  (when-let [thread (::timer client)]
    (stop-thread! thread))
  (let [period (* 1000 (:timer-period client 10))
        thread (start-thread! client period)]
    (assoc client ::timer thread)))


(defn stop
  "Stop the given Vault client. Returns a stopped version of the component."
  [client]
  (when-let [thread (::timer client)]
    (stop-thread! thread))
  (dissoc client ::timer))


;; ## Client Construction

(defn new-client
  "Constructs a new Vault client from a URI address by dispatching on the
  scheme. The client will be returned in an initialized but not started state."
  [address & {:as opts}]
  (let [uri (URI/create address)]
    (case (.getScheme uri)
      "mock"
      (mock/mock-client address opts)

      ("http" "https")
      (http/http-client address opts)

      ;; unknown scheme
      (throw (IllegalArgumentException.
               (str "Unsupported Vault address scheme: " (pr-str address)))))))


(defn config-client
  "Configure a client from the environment if possible. Returns the initialized
  client component, or throws an exception."
  []
  (let [address (or (System/getProperty "vault.addr")
                    (System/getenv "VAULT_ADDR")
                    "mock:-")
        token (or (System/getProperty "vault.token")
                  (System/getenv "VAULT_TOKEN")
                  (let [token-file (io/file (System/getProperty "user.home") ".vault-token")]
                    (when (.exists token-file)
                      (str/trim (slurp token-file)))))
        client (new-client address)]
    (when-not (str/blank? token)
      (proto/authenticate! client token))
    client))
