(ns vault.client.mock
  "A mock in-memory Vault client for local testing."
  (:require
    [clojure.edn :as edn]
    [clojure.java.io :as io]
    [clojure.string :as str]
    [vault.auth :as auth]
    [vault.client :as vault]
    [vault.client.handler :as h]))


;; ## Mock Client

(defrecord MockClient
  [memory handler auth]

  vault/Client

  (auth-info
    [_]
    @auth)


  (authenticate!
    [this auth-info]
    (let [auth-info (if (string? auth-info)
                      {::auth/client-token auth-info}
                      auth-info)]
      (when-not (and (map? auth-info) (::auth/client-token auth-info))
        (throw (IllegalArgumentException.
                 "Client authentication must be a map of information containing a client-token.")))
      (reset! auth auth-info)
      this)))


;; ## Constructors

;; Privatize automatic constructors.
(alter-meta! #'->MockClient assoc :private true)
(alter-meta! #'map->MockClient assoc :private true)


(defn- load-fixtures
  "Helper method to load fixture data from a path. The path may resolve to a
  resource on the classpath, a file on the filesystem, or be `-` to specify no
  data."
  [path]
  (when (not= path "-")
    (some->
      (or (io/resource path)
          (let [file (io/file path)]
            (when (.exists file)
              file)))
      (slurp)
      (edn/read-string))))


(defn mock-client
  "Constructs a new mock Vault client. Accepts a URI address for loading mock
  data, or may be given a map of initial values to directly populate the
  in-memory state."
  ([]
   (mock-client {}))
  ([addr-or-data & {:as opts}]
   (let [initial (cond
                   (map? addr-or-data)
                   addr-or-data

                   (str/starts-with? (str addr-or-data) "mock:")
                   (let [path (subs (str addr-or-data) 5)]
                     (or (load-fixtures path) {}))

                   :else
                   (throw (IllegalArgumentException.
                            (str "Mock client must be constructed with a map of data or a URN with scheme 'mock': "
                                 (pr-str addr-or-data)))))]
     (map->MockClient
       (merge {:handler h/sync-handler}
              opts
              {:auth (auth/new-state)
               :memory (atom initial :validator map?)})))))


;; ## Request Functions

(defn ^:no-doc success-response
  "Helper which uses the handler to generate a successful response."
  [client data]
  (let [handler (:handler client)]
    (h/call
      handler nil
      (fn success
        [state]
        (h/on-success! handler state data)))))


(defn ^:no-doc error-response
  "Helper which uses the handler to generate an error response."
  [client ex]
  (let [handler (:handler client)]
    (h/call
      handler nil
      (fn error
        [state]
        (h/on-error! handler state ex)))))
