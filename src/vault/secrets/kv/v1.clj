(ns vault.secrets.kv.v1
  "The kv secrets engine is used to store arbitrary secrets within the
  configured physical storage for Vault. Writing to a key in the kv-v1 backend
  will replace the old value; sub-fields are not merged together.

  Reference: https://www.vaultproject.io/api-docs/secret/kv/kv-v1"
  (:require
    [clojure.data.json :as json]
    [clojure.string :as str]
    [vault.client.http :as http]
    [vault.client.mock :as mock]
    [vault.client.util :as u])
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
  different mount, prefix the path with the mount location and a colon, like
  `kv1:foo/bar`."

  (list-secrets
    [client path]
    "List the secret names located under a path prefix location. Returns a
    vector of name strings, where further folders are suffixed with `/`. The
    path must be a folder; calling this method on a file or a prefix which does
    not exist will return nil.")

  ;; TODO: lease controls/metadata?
  (read-secret
    [client path]
    [client path opts]
    "Read the secret at the provided path. Returns the secret data, if
    present. Note that Vault internally stores data as JSON, so not all
    Clojure types will round-trip successfully!

    Options:
    - `:not-found`
      If no secret exists at the given path, return this value instead of
      throwing an exception.")

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

  (list-secrets
    [client path]
    (let [[mount path] (u/resolve-path default-mount path)
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
     (let [[mount path] (u/resolve-path default-mount path)]
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
                      {:vault.secrets/mount mount
                       :vault.secrets/path path})))))))


  (write-secret!
    [client path data]
    (let [[mount path] (u/resolve-path default-mount path)]
      (swap! (:memory client)
             assoc-in
             [:secrets mount path]
             (json/write-str data))
      (mock/success-response client nil)))


  (delete-secret!
    [client path]
    (let [[mount path] (u/resolve-path default-mount path)]
      (swap! (:memory client) update-in [:secrets mount] dissoc path)
      (mock/success-response client nil))))


;; ## HTTP Client

(extend-type HTTPClient

  API

  (list-secrets
    [client path]
    (let [[mount path] (u/resolve-path default-mount path)]
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
             (when-not (and (empty? (:vault.client/errors data))
                            (= 404 (:vault.client/status data)))
               ex)))})))


  (read-secret
    ([client path]
     (read-secret client path nil))
    ([client path opts]
     (let [[mount path] (u/resolve-path default-mount path)]
       ;; TODO: check for a cached secret and re-use it
       ;; TODO: update lease cache if appropriate (note: no lease_id, substitute request_id)
       (http/call-api
         client :get (u/join-path mount path)
         {:handle-response
          (fn handle-response
            [body]
            (let [lease-duration (get body "lease_duration")
                  renewable? (get body "renewable")]
              (-> (get body "data")
                  (u/walk-keys keyword)
                  (vary-meta assoc
                             :vault.secrets/mount mount
                             :vault.secrets/path path)
                  (cond->
                    (pos-int? lease-duration)
                    (vary-meta assoc
                               :vault.lease/duration lease-duration
                               :vault.lease/expires-at (.plusSeconds (u/now) lease-duration))

                    (some? renewable?)
                    (vary-meta assoc :vault.lease/renewable? renewable?)))))
          :handle-error
          (fn handle-error
            [ex]
            (let [data (ex-data ex)]
              (if (and (empty? (:vault.client/errors data))
                       (= 404 (:vault.client/status data)))
                (if (contains? opts :not-found)
                  (:not-found opts)
                  (ex-info (str "No kv-v1 secret found at " mount ":" path)
                           data))
                ex)))}))))


  (write-secret!
    [client path data]
    (let [[mount path] (u/resolve-path default-mount path)]
      ;; TODO: invalidate lease cache
      (http/call-api
        client :post (u/join-path mount path)
        {:content-type :json
         :body data})))


  (delete-secret!
    [client path]
    (let [[mount path] (u/resolve-path default-mount path)]
      ;; TODO: invalidate lease cache
      (http/call-api
        client :delete (u/join-path mount path)
        {}))))
