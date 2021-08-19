(ns vault.integration
  "Integration test support code. Manages running a local Vault server in
  development mode in order to truly exercise the client code."
  (:require
    [clojure.java.io :as io]
    [clojure.java.shell :as shell]
    [clojure.string :as str]
    [vault.client.http]
    [vault.core :as vault])
  (:import
    (java.net
      InetSocketAddress
      Socket
      SocketTimeoutException)
    java.util.List
    java.util.concurrent.TimeUnit))


;; ## Development Server

(def interface
  "Local interface to bind the server to."
  "127.0.0.1")


(def port
  "Local port to bind the server to."
  8201)


(def address
  "Local address the development server is bound to."
  (str "http://" interface ":" port))


(def root-token
  "Root token set for the development server."
  "t0p-53cr3t")


(defn start-server!
  "Start a local Vault development server process. Returns the child process
  object."
  ^Process
  []
  (let [command ["vault" "server" "-dev"
                 (str "-dev-listen-address=" (str interface ":" port))
                 (str "-dev-root-token-id=" root-token)
                 "-dev-no-store-token"]
        work-dir (io/file "target/vault")
        builder (doto (ProcessBuilder. ^List command)
                  (.directory work-dir)
                  (.redirectErrorStream true)
                  (.redirectOutput (io/file work-dir "vault.log")))]
    (.mkdirs work-dir)
    (.start builder)))


(defn- port-open?
  "Returns true if the given port is open, false otherwise."
  [host port]
  (let [socket-addr (InetSocketAddress. (str host) (long port))
        socket (Socket.)]
    (try
      (.connect socket socket-addr 10)
      true
      (catch SocketTimeoutException _
        false)
      (catch Exception _
        false)
      (finally
        (.close socket)))))


(defn await-server
  "Wait until the server port is available, trying up to `n` times, sleeping
  for `ms` between each attempt."
  [n ms]
  (loop [i 0]
    (if (< i n)
      (when-not (port-open? interface port)
        (Thread/sleep ms)
        (recur (inc i)))
      (throw (ex-info (format "Vault server not available on port %d after %d attempts (%d ms)"
                              port n (* n ms))
                      {:address address})))))


(defn stop-server!
  "Stop the local development server process."
  [^Process proc]
  (when (.isAlive proc)
    (.destroy proc)
    (when-not (.waitFor proc 5 TimeUnit/SECONDS)
      (binding [*out* *err*]
        (println "Server did not stop cleanly after 5 seconds! Terminating..."))
      (.destroyForcibly proc)))
  (let [exit (.exitValue proc)]
    (when-not (zero? exit)
      (binding [*out* *err*]
        (println "Vault server exited with code:" exit))))
  nil)


;; ## Client Setup

(defn test-client
  "Construct a new test client pointed at the local development server."
  []
  (doto (vault/new-client address)
    (vault/authenticate! :token root-token)))


(defmacro with-dev-server
  "Macro which executes the provided body with a development vault server and
  initialized test client bound to `client`."
  [& body]
  `(let [proc# (start-server!)]
     (try
       (await-server 10 100)
       (let [~'client (test-client)]
         ~@body)
       (finally
         (stop-server! proc#)))))


;; ## Utilities

(defn cli
  "Perform a vault command by shelling out to the command-line client. Useful
  for actions which have not been implemented in the Clojure client yet.
  Returns the parsed JSON result of the command, or throws an exception if the
  command fails."
  [& args]
  (let [result (shell/with-sh-env {"VAULT_ADDR" address
                                   "VAULT_TOKEN" root-token
                                   "VAULT_FORMAT" "json"}
                                  (apply shell/sh (cons "vault" args)))]
    (if (zero? (:exit result))
      ;; Command succeeded, parse result.
      ;; TODO: parse json
      (:out result)
      ;; Command failed.
      (throw (ex-info (format "vault command failed: %s (%d)"
                              (str/join " " args)
                              (:exit result))
                      {:args args
                       :exit (:exit result)
                       :out (:out result)
                       :err (:err result)})))))
