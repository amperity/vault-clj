(ns vault.client.mock
  "A mock in-memory Vault client for local testing."
  (:require
    [clojure.edn :as edn]
    [clojure.java.io :as io]
    [vault.client :as vault]
    [vault.client.response :as resp])
  (:import
    java.net.URI))


;; ## Mock Client

(defrecord MockClient
  [memory response-handler]

  vault/Client

  ,,,)


;; ## Constructors

;; Privatize automatic constructors.
(alter-meta! #'->MockClient assoc :private true)
(alter-meta! #'map->MockClient assoc :private true)


(defn mock-client
  "Constructs a new mock Vault client. May be given a map of initial values to
  populate the in-memory state."
  ([]
   (mock-client {}))
  ([initial-memory & {:as opts}]
   (map->MockClient
     (merge {:response-handler resp/sync-handler}
            opts
            {:memory (atom initial-memory
                           :validator map?)}))))


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


(defmethod vault/new-client "mock"
  [address]
  (let [uri (URI. address)
        path (.getSchemeSpecificPart uri)
        data (load-fixtures path)]
    (mock-client (or data {}))))
