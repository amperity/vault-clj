(ns vault.client
  "Main Vault client namespace. Contains functions for generic client
  operations, constructing new clients, and using clients as components in a
  larger system."
  (:require
    [clojure.java.io :as io]
    [clojure.string :as str]
    [clojure.tools.logging :as log]
    [vault.auth :as auth]
    [vault.auth.token :as auth.token]
    [vault.client.flow :as f]
    [vault.client.http :as http]
    [vault.client.mock :as mock]
    [vault.client.proto :as proto]
    [vault.lease :as lease]
    [vault.sys.leases :as sys.leases]
    [vault.sys.wrapping :as sys.wrapping])
  #?(:bb
     (:import
       java.net.URI)
     :clj
     (:import
       java.net.URI
       (java.util.concurrent
         ExecutorService
         ScheduledExecutorService
         ScheduledThreadPoolExecutor
         TimeUnit))))


;; ## Protocol Methods

(defn client?
  "True if the given value satisfies the client protocol."
  [x]
  (satisfies? proto/Client x))


(defn auth-info
  "Return the client's current auth information, a map containing the
  `:vault.auth/token` and other metadata keys from the `vault.auth`
  namespace. Returns nil if the client is unauthenticated."
  [client]
  (proto/auth-info client))


(defn authenticate!
  "Manually authenticate the client by providing a map of auth information
  containing a `:vault.auth/token`. As a shorthand, a Vault token string may
  be provided directly. Returns the client."
  [client auth-info]
  (proto/authenticate! client auth-info))


;; ## Maintenance Task

(defn- maintenance-task
  "Construct a new runnable task to perform client authentication and lease
  maintenance work."
  ^Runnable
  [client]
  (fn tick
    []
    (try
      (auth/maintain!
        (:auth client)
        #(f/call-sync auth.token/renew-token! client {}))
      (lease/maintain!
        client
        #(f/call-sync sys.leases/renew-lease!
                      client
                      (::lease/id %)
                      (::lease/renew-increment %)))
      (catch InterruptedException _
        nil)
      (catch Exception ex
        (log/error ex "Unhandled error while running maintenance task!")))))


#?(:bb nil
   :clj
   (defn start
     "Start the Vault component, returning an updated version with a periodic
          maintenance task.

          Behavior may be controlled with the following keys on the client:
          - `:maintenance-period`
          How frequently to check the client auth token and leased secrets for
          renewal or rotation. Defaults to every 10 seconds.
          - `:maintenance-executor`
          Custom scheduled executor service to use for executing maintenance tasks.
          Defaults to a single-threaded executor.
          - `:callback-executor`
          Custom executor service to use for executing lease callbacks. Defaults to
          the Clojure agent send-off pool."
     [client]
     (when-let [task (:maintenance-task client)]
       (future-cancel task))
     (let [period (:maintenance-period client 10)
           executor (or (:maintenance-executor client)
                        (ScheduledThreadPoolExecutor. 1))]
       (log/info "Scheduling vault maintenance task to run every" period "seconds")
       (assoc client
              :maintenance-executor executor
              :maintenance-task (.scheduleAtFixedRate
                                  ^ScheduledExecutorService executor
                                  (maintenance-task client)
                                  period period TimeUnit/SECONDS)))))


#?(:bb nil
   :clj
   (defn stop
     "Stop the given Vault client. Returns a stopped version of the component."
     [client]
     (when-let [task (:maintenance-task client)]
       (log/debug "Canceling vault maintenance task")
       (future-cancel task))
     (when-let [maintenance-executor (:maintenance-executor client)]
       (log/debug "Shutting down vault maintenance executor")
       (.shutdownNow ^ExecutorService maintenance-executor))
     (when-let [callback-executor (:callback-executor client)]
       (log/debug "Shutting down Vault callback executor")
       (.shutdownNow ^ExecutorService callback-executor))
     (dissoc client :maintenance-task :maintenance-executor :callback-executor)))


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
  client, or throws an exception."
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


(defn config-wrapped-client
  "Construct a new client for the URI address and authenticate it with a
  wrapped single-use token. Returns the initialized client, or throws an
  exception."
  [address token]
  (let [client (new-client address)
        _ (proto/authenticate! client token)
        result (sys.wrapping/unwrap client)]
    (proto/authenticate! client (:client-token result))
    (auth.token/resolve-auth! client)
    client))
