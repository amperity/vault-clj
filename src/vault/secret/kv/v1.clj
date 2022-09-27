(ns vault.secret.kv.v1
  "The kv secrets engine is used to store arbitrary secrets within the
  configured physical storage for Vault. Writing to a key in the kv-v1 backend
  will replace the old value; sub-fields are not merged together.

  Reference: https://www.vaultproject.io/api-docs/secret/kv/kv-v1"
  (:require
    [clojure.data.json :as json]
    [vault.client.http :as http]
    [vault.client.mock :as mock]
    [vault.lease :as lease]
    [vault.util :as u])
  (:import
    vault.client.http.HTTPClient
    vault.client.mock.MockClient))


(def default-mount
  "Default mount point to use if one is not provided."
  "secret")


;; ## API Protocol

(defprotocol API
  "The kv secrets engine is used to store arbitrary static secrets within
  Vault.

  All of the methods in this protocol expect `path` to be relative to the
  secret engine mount point, which defaults to `secret/`. To specify a
  different mount, use `with-mount`."

  (with-mount
    [client mount]
    "Return an updated client which will resolve secrets against the provided
    mount instead of the default. Passing `nil` will reset the client to the
    default.")

  (list-secrets
    [client path]
    "List the secret names located under a path prefix location. Returns a map
    with a `:keys` vector of name strings, where further folders are suffixed
    with `/`. The path must be a folder; calling this method on a file or a
    prefix which does not exist will return nil.")

  (read-secret
    [client path]
    [client path opts]
    "Read the secret at the provided path. Returns the secret data, if present.
    Throws an exception or returns the provided not-found value if not.

    Note that Vault internally stores data as JSON, so not all Clojure types
    will round-trip successfully!

    Options:
    - `:not-found`
      If no secret exists at the given path, return this value instead of
      throwing an exception.
    - `:refresh?`
      Always make a read for fresh data, even if a cached secret is
      available.
    - `:ttl`
      Cache the data read for the given number of seconds. Overrides the TTL
      returned by Vault. A value of zero or less will disable caching.")

  (write-secret!
    [client path data]
    "Store secret data at the provided path, overwriting any secret that was
    previously stored there. Returns nil.

    Writing a `:ttl` key as part of the secret will control the pseudo lease
    duration returned when the secret is read. Note that Vault internally
    stores data as JSON, so not all Clojure types will round-trip
    successfully!")

  (delete-secret!
    [client path]
    "Delete the secret at the provided path, if any. Returns nil."))


;; ## Mock Client

(extend-type MockClient

  API

  (with-mount
    [client mount]
    (if (some? mount)
      (assoc client ::mount mount)
      (dissoc client ::mount)))


  (list-secrets
    [client path]
    (let [mount (::mount client default-mount)
          path (u/trim-path path)
          data (get-in @(:memory client) [::data mount])
          result (mock/list-paths (keys data) path)]
      (mock/success-response
        client
        (when (seq result)
          {:keys result}))))


  (read-secret
    ([client path]
     (read-secret client path nil))
    ([client path opts]
     (let [mount (::mount client default-mount)
           path (u/trim-path path)]
       (if-let [secret (get-in @(:memory client) [::data mount path])]
         (mock/success-response
           client
           (-> secret
               (json/read-str)
               (u/keywordize-keys)))
         (if (contains? opts :not-found)
           (mock/success-response client (:not-found opts))
           (mock/error-response
             client
             (ex-info (str "No kv-v1 secret found at " mount ":" path)
                      {::mount mount
                       ::path path})))))))


  (write-secret!
    [client path data]
    (let [mount (::mount client default-mount)
          path (u/trim-path path)]
      (swap! (:memory client)
             assoc-in
             [::data mount path]
             (json/write-str (u/stringify-keys data)))
      (mock/success-response client nil)))


  (delete-secret!
    [client path]
    (let [mount (::mount client default-mount)
          path (u/trim-path path)]
      (swap! (:memory client) update-in [::data mount] dissoc path)
      (mock/success-response client nil))))


;; ## HTTP Client

(defn- synthesize-lease
  "Produce a synthetic map of lease information from the given raw lease, cache
  key, and an optional custom TTL. Returns nil if the TTL is present and
  non-positive."
  [lease cache-key ttl]
  (when (or (and (::lease/duration lease)
                 (nil? ttl))
            (pos? ttl))
    (-> lease
        (assoc ::lease/id (str (random-uuid))
               ::lease/key cache-key)
        (cond->
          ttl
          (assoc ::lease/duration (long ttl)
                 ::lease/expires-at (.plusSeconds (u/now) (long ttl)))))))


(extend-type HTTPClient

  API

  (with-mount
    [client mount]
    (if (some? mount)
      (assoc client ::mount mount)
      (dissoc client ::mount)))


  (list-secrets
    [client path]
    (let [mount (::mount client default-mount)
          path (u/trim-path path)]
      (http/call-api
        client :get (u/join-path mount path)
        {:query-params {:list true}
         :handle-response
         (fn handle-response
           [body]
           (u/kebabify-keys (get body "data")))
         :handle-error
         (fn handle-error
           [ex]
           (when-not (http/not-found? ex)
             ex))})))


  (read-secret
    ([client path]
     (read-secret client path nil))
    ([client path opts]
     (let [mount (::mount client default-mount)
           path (u/trim-path path)
           cache-key [::secret mount path]
           cached (when-not (:refresh? opts)
                    (lease/find-data (:leases client) cache-key))]
       (if cached
         (http/cached-response client cached)
         (http/call-api
           client :get (u/join-path mount path)
           {:handle-response
            (fn handle-response
              [body]
              (let [lease (synthesize-lease
                            (http/lease-info body)
                            cache-key
                            (:ttl opts))
                    data (-> (get body "data")
                             (u/keywordize-keys)
                             (vary-meta assoc
                                        ::mount mount
                                        ::path path))]
                (when lease
                  (lease/invalidate! (:leases client) cache-key)
                  (lease/put! (:leases client) lease data))
                (vary-meta data merge lease)))
            :handle-error
            (fn handle-error
              [ex]
              (if (http/not-found? ex)
                (if (contains? opts :not-found)
                  (:not-found opts)
                  (ex-info (str "No kv-v1 secret found at " mount ":" path)
                           (ex-data ex)))
                ex))})))))


  (write-secret!
    [client path data]
    (let [mount (::mount client default-mount)
          path (u/trim-path path)
          cache-key [::secret mount path]]
      (lease/invalidate! (:leases client) cache-key)
      (http/call-api
        client :post (u/join-path mount path)
        {:content-type :json
         :body (u/stringify-keys data)})))


  (delete-secret!
    [client path]
    (let [mount (::mount client default-mount)
          path (u/trim-path path)
          cache-key [::secret mount path]]
      (lease/invalidate! (:leases client) cache-key)
      (http/call-api
        client :delete (u/join-path mount path)
        {}))))
