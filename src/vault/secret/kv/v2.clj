(ns vault.secret.kv.v2
  "The kv secrets engine is used to store arbitrary secrets within the
  configured physical storage for Vault. Writing to a key in the kv-v1 backend
  will replace the old value; sub-fields are not merged together.

  Reference: https://www.vaultproject.io/api-docs/secret/kv/kv-v1"
  (:require
    [clojure.data.json :as json]
    [clojure.string :as str]
    [vault.client.http :as http]
    [vault.client.mock :as mock]
    [vault.lease :as lease]
    [vault.util :as u])
  (:import
    java.time.Instant
    vault.client.http.HTTPClient
    vault.client.mock.MockClient))


(def default-mount
  "Default mount point to use if one is not provided."
  "secret")


;; ## API Protocol

(defprotocol API
  "The kv secrets engine is used to store arbitrary static secrets within
  Vault. V2 of the engine enables secret versioning and metadata capabilities.

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
    Throws an exception or returns the provided not-found value if not. The
    returned value will have the additional information about the secret, such
    as the version, attached as metadata.

    Note that Vault internally stores data as JSON, so not all Clojure types
    will round-trip successfully!

    Options:
    - `:version`
      Read a specific version of the secret. Defaults to the latest version.
    - `:not-found`
      If no secret exists at the given path or version, return this value
      instead of throwing an exception.
    - `:refresh?`
      Always make a read for fresh data, even if a cached secret is
      available.
    - `:ttl`
      Cache the data read for the given number of seconds. A value of zero or
      less will disable caching.")

  (write-secret!
    [client path data]
    [client path data opts]
    "Store data at the provided path, creating a new version of the secret.
    Returns the secret metadata.

    Note that Vault internally stores data as JSON, so not all Clojure types
    will round-trip successfully!

    Options:
    - `:cas`
      In order for a write to be successful, this must be set to the current
      version of the secret. If cas is set to 0, the write will only be allowed
      if the key doesn't exist.")

  (patch-secret!
    [client path data]
    [client path data opts]
    "Patch an existing secret at the provided location. The secret must neither
    be deleted nor destroyed. A new version will be created upon successfully
    applying a patch with the provided data. Returns the secret metadata.

    Note that Vault internally stores data as JSON, so not all Clojure types
    will round-trip successfully!

    Options:
    - `:cas`
      In order for a write to be successful, this must be set to the current
      version of the secret. If cas is set to 0, the write will only be allowed
      if the key doesn't exist.")

  (delete-secret!
    [client path]
    "Delete the latest version of the secret at the provided path, if any. Returns nil.

    This is a soft-delete that may later be reverted with `undelete-versions!`.")

  (destroy-secret!
    [client path]
    "Permanently delete the secret metadata and all version data for the given
    path. All version history will be removed. Returns nil.")

  (delete-versions!
    [client path versions]
    "Issue a soft delete of the specified versions of the secret. Returns nil.

    This marks the versions as deleted and will stop them from being returned
    from reads, but the underlying data will not be removed. A delete can be
    undone using the `undelete-versions!` method.")

  (undelete-versions!
    [client path versions]
    "Undelete the data for the provided versions of the secret. Returns nil.

    This restores the data, allowing it to be returned on get requests.")

  (destroy-versions!
    [client path versions]
    "Permanently remove the data for the provided secret and version numbers.
    Returns nil.")

  (read-metadata
    [client path]
    "Read the metadata and versions for the secret at the specified path.
    Metadata is version-agnostic.")

  (write-metadata!
    [client path opts]
    "Update the metadata of a secret at the specified path. Returns nil. This
    does not create a new version.

    Options:
    - `:max-versions`
      Number of versions to keep per key. Once the secret has more than the
      configured allowed versions, the oldest version will be permanently
      deleted.
    - `:cas-required`
      If true, the key will require the cas parameter to be set on all write requests.
    - `:delete-version-after`
      Duration string specifying the time after which all new versions written
      to this secret should be deleted. Accepts Go duration format strings.
    - `:custom-metadata`
      Map of arbitrary string-to-string valued user-provided metadata meant to
      describe the secret.")

  (patch-metadata!
    [client path opts]
    "Patch the existing metadata for the secret at the provided location.
    Returns nil.

    See `write-metadata!` for options."))


;; ## Mock Client

(defn- qualify-keyword
  "Update a keyword so that it is namespaced by 'vault.secret.kv.v2'."
  [k]
  (keyword "vault.secret.kv.v2" (name k)))


(defn- ex-not-found
  "Construct a new not-found exception for the given mount and path."
  ([mount path]
   (ex-not-found mount path nil))
  ([mount path data]
   (ex-info (str "No kv-v2 secret found at " mount ":" path)
            (assoc data
                   ::mount mount
                   ::path path))))


(defn- mock-patch
  "Given an existing map value and a JSON-patch-style map, apply the updates."
  [m u]
  (reduce
    (fn maybe-update
      [acc k]
      (cond
        ;; key is on both sides
        (and (contains? m k) (contains? u k))
        (let [mv (get m k)
              uv (get m k)]
          (cond
            ;; nil overwrites original entry to remove it, so we just don't add
            ;; it here
            (nil? uv)
            acc

            ;; if both sides are maps, recurse
            (and (map? mv) (map? uv))
            (assoc acc k (mock-patch mv uv))

            ;; otherwise overwrite
            :else
            (assoc acc k uv)))

        ;; key only in original
        (contains? m k)
        (assoc acc k (get m k))

        ;; key only in updates
        :else
        (assoc acc k (get u k))))
    (empty m)
    (set (concat (keys m) (keys u)))))


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
           path (u/trim-path path)
           secret (get-in @(:memory client) [::data mount path])
           version-id (or (:version opts)
                          (:current-version secret))
           version (get (:versions secret) version-id)]
       (cond
         (and version
              (or (nil? (:deletion-time version))
                  (.isBefore (u/now) (:deletion-time version)))
              (not (:destroyed version)))
         (mock/success-response
           client
           (-> (:data version)
               (json/read-str)
               (u/keywordize-keys)
               (vary-meta merge
                          {::mount mount
                           ::path path
                           ::version version-id
                           ::created-time (:created-time version)
                           ::destroyed false}
                          (when-let [cm (:custom-metadata secret)]
                            {::custom-metadata (json/read-str cm)}))))

         (contains? opts :not-found)
         (mock/success-response client (:not-found opts))

         :else
         (mock/error-response client (ex-not-found mount path))))))


  (write-secret!
    ([client path data]
     (write-secret! client path data nil))
    ([client path data opts]
     (let [mount (::mount client default-mount)
           path (u/trim-path path)
           serialized (json/write-str data)]
       (mock/update-secret!
         client
         [::data mount path]
         (fn update-secret
           [secret]
           (let [current (:current-version secret 0)
                 cas (:cas opts)]
             (when (and cas (not= cas current))
               (throw (ex-info (str "Write failed, cas wanted version " cas
                                    " but found secret version " current)
                               {::mount mount
                                ::path path
                                ::version current}))))
           (let [max-version (if (seq (:versions secret))
                               (apply max (keys (:verisons secret)))
                               0)
                 version-id (inc max-version)
                 version {:data serialized
                          :created-time (u/now)
                          :destroyed false}]
             (-> (or secret {:created-time (u/now)})
                 (assoc-in [:versions version-id] version)
                 (assoc :current-version version-id
                        :updated-time (u/now)))))))))


  (patch-secret!
    ([client path data]
     (patch-secret! client path data nil))
    ([client path data opts]
     (let [mount (::mount client default-mount)
           path (u/trim-path path)]
       (mock/update-secret!
         client
         [::data mount path]
         (fn update-secret
           [secret]
           (let [current (:current-version secret 0)
                 version (get (:versions secret) current)
                 cas (:cas opts)]
             (when (or (nil? version)
                       (and (:deletion-time version)
                            (.isAfter (u/now) (:deletion-time version)))
                       (:destroyed version))
               (mock/error-response client (ex-not-found mount path)))
             (when (and cas (not= cas current))
               (throw (ex-info (str "Write failed, cas wanted version " cas
                                    " but found secret version " current)
                               {::mount mount
                                ::path path
                                ::version current})))
             (let [new-data (mock-patch (:data version) data)
                   max-version (if (seq (:versions secret))
                                 (apply max (keys (:verisons secret)))
                                 0)
                   version-id (inc max-version)
                   version {:data (json/write-str new-data)
                            :created-time (u/now)
                            :destroyed false}]
               (-> secret
                   (assoc-in [:versions version-id] version)
                   (assoc :current-version version-id)))))))))


  (delete-secret!
    [client path]
    (let [mount (::mount client default-mount)
          path (u/trim-path path)]
      (mock/update-secret!
        client
        [::data mount path]
        (fn update-secret
          [secret]
          (when-not (:current-version secret)
            (throw (ex-not-found mount path)))
          (assoc-in secret [:versions (:current-version secret) :deletion-time] (u/now))))))


  (destroy-secret!
    [client path]
    (let [mount (::mount client default-mount)
          path (u/trim-path path)]
      (swap! (:memory client) update-in [::data mount] dissoc path)
      (mock/success-response client nil)))


  (delete-versions!
    [client path versions]
    (let [mount (::mount client default-mount)
          path (u/trim-path path)
          versions (set versions)]
      (mock/update-secret!
        client
        [::data mount path]
        (fn update-secret
          [secret]
          (when-not secret
            (throw (ex-not-found mount path)))
          (assoc secret
                 :versions
                 (reduce
                   (fn set-deletions
                     [acc version-id]
                     (if (contains? acc version-id)
                       (assoc-in acc [version-id :deletion-time] (u/now))
                       acc))
                   (:versions secret)
                   versions))))))


  (undelete-versions!
    [client path versions]
    (let [mount (::mount client default-mount)
          path (u/trim-path path)
          versions (set versions)]
      (mock/update-secret!
        client
        [::data mount path]
        (fn update-secret
          [secret]
          (when-not secret
            (throw (ex-not-found mount path)))
          (assoc secret
                 :versions
                 (reduce
                   (fn unset-deletions
                     [acc version-id]
                     (if (contains? acc version-id)
                       (update acc version-id dissoc :deletion-time)
                       acc))
                   (:versions secret)
                   versions))))))


  (destroy-versions!
    [client path versions]
    (let [mount (::mount client default-mount)
          path (u/trim-path path)
          versions (set versions)]
      (mock/update-secret!
        client
        [::data mount path]
        (fn update-secret
          [secret]
          (when-not secret
            (throw (ex-not-found mount path)))
          (assoc secret
                 :versions
                 (reduce
                   (fn destroy
                     [acc version-id]
                     (if (contains? acc version-id)
                       (-> acc
                           (update version-id dissoc :data)
                           (assoc-in [version-id :destroyed] true))
                       acc))
                   (:versions secret)
                   versions))))))

  (read-metadata
    [client path]
    (let [mount (::mount client default-mount)
          path (u/trim-path path)
          secret (get-in @(:memory client) [::data mount path])]
      (if secret
        (mock/success-response
          client
          (-> (merge {:max-versions 0
                      :cas-required false
                      :delete-version-after "0s"}
                     secret)
              (select-keys [:created-time
                            :updated-time
                            :current-version])
              (assoc :oldest-version (if (seq (:versions secret))
                                       (->> (:versions secret)
                                            (sort-by (comp :created-time val))
                                            (first)
                                            (key))
                                       0)
                     :versions (into {}
                                     (map (juxt key #(select-keys (val %)
                                                                  [:created-time
                                                                   :deletion-time
                                                                   :destroyed])))
                                     (:versions secret)))))
        (mock/error-response client (ex-not-found mount path)))))


  (write-metadata!
    [client path opts]
    (let [mount (::mount client default-mount)
          path (u/trim-path path)]
      (mock/update-secret!
        client
        [::data mount path]
        (fn update-secret
          [secret]
          (when-not secret
            (throw (ex-not-found mount path)))
          (-> secret
              (merge (select-keys opts
                                  [:max-versions
                                   :cas-required
                                   :delete-version-after]))
              (cond->
                (seq (:custom-metadata opts))
                (assoc :custom-metadata (-> (:custom-metadata opts)
                                            (u/stringify-keys)
                                            (json/write-str)))))))))


  (patch-metadata!
    [client path opts]
    (let [mount (::mount client default-mount)
          path (u/trim-path path)]
      (mock/update-secret!
        client
        [::data mount path]
        (fn update-secret
          [secret]
          (when-not secret
            (throw (ex-not-found mount path)))
          (-> secret
              (merge (select-keys opts
                                  [:max-versions
                                   :cas-required
                                   :delete-version-after]))
              (cond->
                (seq (:custom-metadata opts))
                (assoc :custom-metadata (-> (:custom-metadata secret)
                                            (json/read-str)
                                            (u/keywordize-keys)
                                            (mock-patch (:custom-metadata opts))
                                            (u/stringify-keys)
                                            (json/write-str))))))))))


;; ## HTTP Client

(defn- parse-meta-times
  "Update the data map by parsing any keywords which end in '-time' as
  Instants. Entries with blank values will be removed."
  [data]
  (into (empty data)
        (keep
          (fn [[k v :as entry]]
            (if (str/ends-with? (name k) "-time")
              (when-not (str/blank? v)
                [k (Instant/parse v)])
              entry)))
        data))


(defn- parse-secret-metadata
  "Interpret a secret metadata response."
  [body]
  (let [raw-data (get body "data")]
    (-> raw-data
        (dissoc "custom_metadata" "versions")
        (u/kebabify-keys)
        (parse-meta-times)
        (merge
          (when-let [cm (get raw-data "custom_metadata")]
            {:custom-metadata (u/keywordize-keys cm)})
          (when-let [versions (not-empty (get raw-data "versions"))]
            {:versions (into (sorted-map)
                             (map
                               (fn parse-version
                                 [[version-id-str version]]
                                 [(or (parse-long version-id-str) version-id-str)
                                  (-> version
                                      (u/kebabify-keys)
                                      (parse-meta-times))]))
                             versions)})))))


(defn- synthesize-lease
  "Produce a synthetic map of lease information from the cache key and an
  optional custom TTL. Returns nil if the TTL is not a positive number."
  [cache-key ttl]
  (when (and ttl (pos? ttl))
    {::lease/id (str (random-uuid))
     ::lease/key cache-key
     ::lease/duration (long ttl)
     ::lease/expires-at (.plusSeconds (u/now) (long ttl))}))


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
        client :list (u/join-path mount "metadata" path)
        {:handle-response
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
       (if (and cached
                (or (nil? (:version opts))
                    (= (:version opts) (::version (meta cached)))))
         (http/cached-response client cached)
         (http/call-api
           client :get (u/join-path mount "data" path)
           {:query-params (if-let [version (:version opts)]
                            {:version version}
                            {})
            :handle-response
            (fn handle-response
              [body]
              (let [lease (synthesize-lease cache-key (:ttl opts))
                    metadata (-> (get-in body ["data" "metadata"])
                                 (u/kebabify-keys)
                                 (parse-meta-times)
                                 (update-keys qualify-keyword))
                    data (-> (get-in body ["data" "data"])
                             (u/keywordize-keys)
                             (vary-meta merge
                                        metadata
                                        {::mount mount
                                         ::path path}))]
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
                  (ex-not-found mount path (ex-data ex)))
                ex))})))))


  (write-secret!
    ([client path data]
     (write-secret! client path data nil))
    ([client path data opts]
     (let [mount (::mount client default-mount)
           path (u/trim-path path)
           cache-key [::secret mount path]]
       (lease/invalidate! (:leases client) cache-key)
       (http/call-api
         client :post (u/join-path mount "data" path)
         {:content-type :json
          :body {:options (select-keys opts [:cas])
                 :data (u/stringify-keys data)}
          :handle-response parse-secret-metadata}))))


  (patch-secret!
    ([client path data]
     (patch-secret! client path data nil))
    ([client path data opts]
     (let [mount (::mount client default-mount)
           path (u/trim-path path)
           cache-key [::secret mount path]]
       (lease/invalidate! (:leases client) cache-key)
       (http/call-api
         client :patch (u/join-path mount "data" path)
         {:headers {"content-type" "application/merge-patch+json"}
          :body (json/write-str
                  {:options (select-keys opts [:cas])
                   :data data})
          :handle-response parse-secret-metadata}))))


  (delete-secret!
    [client path]
    (let [mount (::mount client default-mount)
          path (u/trim-path path)
          cache-key [::secret mount path]]
      (lease/invalidate! (:leases client) cache-key)
      (http/call-api
        client :delete (u/join-path mount "data" path)
        {})))


  (destroy-secret!
    [client path]
    (let [mount (::mount client default-mount)
          path (u/trim-path path)
          cache-key [::secret mount path]]
      (lease/invalidate! (:leases client) cache-key)
      (http/call-api
        client :delete (u/join-path mount "metadata" path)
        {})))


  (delete-versions!
    [client path versions]
    (let [mount (::mount client default-mount)
          path (u/trim-path path)
          cache-key [::secret mount path]]
      (lease/invalidate! (:leases client) cache-key)
      (http/call-api
        client :post (u/join-path mount "delete" path)
        {:content-type :json
         :body {:versions versions}})))


  (undelete-versions!
    [client path versions]
    (let [mount (::mount client default-mount)
          path (u/trim-path path)
          cache-key [::secret mount path]]
      (lease/invalidate! (:leases client) cache-key)
      (http/call-api
        client :post (u/join-path mount "undelete" path)
        {:content-type :json
         :body {:versions versions}})))


  (destroy-versions!
    [client path versions]
    (let [mount (::mount client default-mount)
          path (u/trim-path path)
          cache-key [::secret mount path]]
      (lease/invalidate! (:leases client) cache-key)
      (http/call-api
        client :post (u/join-path mount "destroy" path)
        {:content-type :json
         :body {:versions versions}})))


  (read-metadata
    [client path]
    (let [mount (::mount client default-mount)
          path (u/trim-path path)]
      (http/call-api
        client :get (u/join-path mount "metadata" path)
        {:handle-response
         (fn handle-response
           [body]
           (-> body
               (parse-secret-metadata)
               (vary-meta assoc
                          ::mount mount
                          ::path path)))})))


  (write-metadata!
    [client path opts]
    (let [mount (::mount client default-mount)
          path (u/trim-path path)]
      (http/call-api
        client :post (u/join-path mount "metadata" path)
        {:content-type :json
         :body (-> opts
                   (dissoc :custom-metadata)
                   (u/snakify-keys)
                   (cond->
                     (seq (:custom-metadata opts))
                     (assoc :custom_metadata
                            (u/stringify-keys (:custom-metadata opts)))))})))


  (patch-metadata!
    [client path opts]
    (let [mount (::mount client default-mount)
          path (u/trim-path path)]
      (http/call-api
        client :patch (u/join-path mount "metadata" path)
        {:headers {"content-type" "application/merge-patch+json"}
         :body (json/write-str
                 (-> opts
                     (dissoc :custom-metadata)
                     (u/snakify-keys)
                     (cond->
                       (seq (:custom-metadata opts))
                       (assoc :custom_metadata
                              (u/stringify-keys (:custom-metadata opts))))))}))))
