(ns vault.secret.kv.v1
  "The kv secrets engine is used to store arbitrary secrets within the
  configured physical storage for Vault. Writing to a key in the kv-v1 backend
  will replace the old value; sub-fields are not merged together.

  Reference: https://www.vaultproject.io/api-docs/secret/kv/kv-v1"
  (:require
    [clojure.data.json :as json]
    [clojure.string :as str]
    [vault.client :as vault]
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
    "List the secret names located under a path prefix location. Returns a
    vector of name strings, where further folders are suffixed with `/`. The
    path must be a folder; calling this method on a file or a prefix which does
    not exist will return nil.")

  (read-secret
    [client path]
    [client path opts]
    "Read the secret at the provided path. Returns the secret data, if
    present. Note that Vault internally stores data as JSON, so not all
    Clojure types will round-trip successfully!

    Options:
    - `:not-found`
      If no secret exists at the given path, return this value instead of
      throwing an exception.
    - `:force-read`
      If true, always read the secret from the server, even if a cached value
      is available.")

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
          data (get-in @(:memory client) [:secrets mount])
          depth (if (str/blank? path)
                  1
                  (inc (count (str/split path #"/"))))]
      (mock/success-response
        client
        (->> (keys data)
             (filter #(or (= "" path) (str/starts-with? % (str path "/"))))
             (map #(let [parts (str/split % #"/")]
                     (if (< depth (count parts))
                       (str (nth parts (dec depth)) "/")
                       (last parts))))
             (distinct)
             (sort)
             (vec)
             (not-empty)))))


  (read-secret
    ([client path]
     (read-secret client path nil))
    ([client path opts]
     (let [mount (::mount client default-mount)
           path (u/trim-path path)]
       (if-let [secret (get-in @(:memory client) [:secrets mount path])]
         (mock/success-response
           client
           (-> secret
               (json/read-str)
               (u/walk-keys keyword)))
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
             [:secrets mount path]
             (json/write-str data))
      (mock/success-response client nil)))


  (delete-secret!
    [client path]
    (let [mount (::mount client default-mount)
          path (u/trim-path path)]
      (swap! (:memory client) update-in [:secrets mount] dissoc path)
      (mock/success-response client nil))))


;; ## HTTP Client

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
           (get-in body ["data" "keys"]))
         :handle-error
         (fn handle-error
           [ex]
           (let [data (ex-data ex)]
             (when-not (and (empty? (::vault/errors data))
                            (= 404 (::vault/status data)))
               ex)))})))


  (read-secret
    ([client path]
     (read-secret client path nil))
    ([client path opts]
     (let [mount (::mount client default-mount)
           path (u/trim-path path)
           api-path (u/join-path mount path)
           cache-key [::secret mount path]]
       (if-let [data (and (not (:force-read opts))
                          (lease/get-data (:leases client) cache-key))]
         ;; Re-use cached secret.
         (http/cached-response client api-path data)
         ;; No cached value available, call API.
         (http/call-api
           client :get api-path
           {:handle-response
            (fn handle-response
              [body]
              (let [lease (http/lease-info body)
                    data (-> (get body "data")
                             (u/walk-keys keyword)
                             (vary-meta assoc
                                        ::mount mount
                                        ::path path))]
                (when lease
                  (lease/put! (:leases client) cache-key lease data))
                (vary-meta data merge lease)))
            :handle-error
            (fn handle-error
              [ex]
              (let [data (ex-data ex)]
                (if (and (empty? (::vault/errors data))
                         (= 404 (::vault/status data)))
                  (if (contains? opts :not-found)
                    (:not-found opts)
                    (ex-info (str "No kv-v1 secret found at " mount ":" path)
                             data))
                  ex)))})))))


  (write-secret!
    [client path data]
    (let [mount (::mount client default-mount)
          path (u/trim-path path)
          cache-key [::secret mount path]]
      (lease/invalidate! (:leases client) cache-key)
      (http/call-api
        client :post (u/join-path mount path)
        {:content-type :json
         :body data})))


  (delete-secret!
    [client path]
    (let [mount (::mount client default-mount)
          path (u/trim-path path)
          cache-key [::secret mount path]]
      (lease/invalidate! (:leases client) cache-key)
      (http/call-api
        client :delete (u/join-path mount path)
        {}))))
