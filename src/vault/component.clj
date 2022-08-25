(ns vault.component
  "Support for Vault clients as components in a larger system."
  (:require
    [clojure.java.io :as io]
    [clojure.string :as str]
    [clojure.tools.logging :as log]
    [vault.client :as vault]
    [vault.client.http]
    [vault.client.mock]
    [vault.lease :as lease])
  (:import
    java.net.URI))


;; ## Timer Logic

(defn- timer-loop
  "Constructs a new runnable looping function which performs the timer logic
  every `period` milliseconds. If the `jitter` property is set, the sleep cycle
  will vary randomly by up to `jitter` percent in length (meaning in the
  range `[p, p+(j*p)]`).

  The loop can be terminated by interrupting the thread."
  ^Runnable
  [handler period jitter]
  (fn tick
    []
    (try
      (while (not (Thread/interrupted))
        (Thread/sleep (+ period (rand-int (long (* jitter period)))))
        (try
          (handler)
          (catch InterruptedException ex
            (throw ex))
          (catch Exception ex
            (log/error ex "Error while running timer handler!"))))
      (catch InterruptedException _
        nil))))


(defn- start-thread!
  "Constructs and starts a new timer thread to call the given handler function.
  The returned thread will be in daemon mode."
  [label handler period]
  (log/infof "Starting %s thread with period of %d ms" label period)
  (doto (Thread. (timer-loop handler period 0.10) (str label))
    (.setDaemon true)
    (.start)))


(defn- stop-thread!
  "Stops a running timer thread cleanly if possible."
  [^Thread thread]
  (when (.isAlive thread)
    (log/debug "Interrupting timer thread" (.getName thread))
    (.interrupt thread)
    (.join thread 1000)))


;; ## Component Lifecycle

(defn start
  "Start the Vault component, returning an updated version with a timer
  thread."
  [client]
  (when-let [thread (::timer client)]
    (stop-thread! thread))
  (let [period (* 1000 (::lease/check-period client 60))
        tick (fn tick
               []
               (lease/maintain-leases! (::lease/store client)))
        thread (start-thread! "vault-client-timer" tick period)]
    (assoc client ::timer thread)))


(defn stop
  "Stop the given Vault client. Returns a stopped version of the component."
  [client]
  (when-let [thread (::timer client)]
    (stop-thread! thread))
  (dissoc client ::timer))


;; ## Client Construction

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
        client (vault/new-client address)]
    (when-not (str/blank? token)
      (vault/authenticate! client token))
    client))
