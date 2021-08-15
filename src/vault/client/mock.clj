(ns vault.client.mock
  "A mock in-memory Vault client for local testing."
  (:require
    [clojure.edn :as edn]
    [clojure.java.io :as io]
    [vault.client :as vault])
  (:import
    java.net.URI))


;; ## Mock Client

(defrecord MockClient
  [memory]

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
  ([initial-memory]
   (map->MockClient
     {:memory (atom initial-memory
                    :validator map?)})))


(defn- load-fixtures
  "Helper method to load fixture data from a path. The path may resolve to a
  resource on the classpath, a file on the filesystem, or be `-` to specify no
  data."
  [location]
  (when (not= location "-")
    (some->
      (or (io/resource location)
          (let [file (io/file location)]
            (when (.exists file)
              file)))
      (slurp)
      (edn/read-string))))


(defmethod vault/new-client "mock"
  [location]
  (let [uri (URI. location)
        location (.getSchemeSpecificPart uri)
        data (load-fixtures location)]
    (mock-client (or data {}))))
