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
    clojure.lang.IObj
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
  secret engine mount point. To specify a custom mount, use `with-mount`."

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
    prefix which does not exist will return `nil`.")

  (read-secret
    [client path]
    [client path opts]
    "Read the secret at the provided path. Returns the secret data, if present.
    Throws an exception or returns the provided not-found value if not. The
    returned value will have the additional information about the secret, such
    as the version, attached as metadata.

    Options:

    - `:version` (integer)

      Read a specific version of the secret. Defaults to the latest version.

    - `:not-found` (any)

      If no secret exists at the given path or version, return this value
      instead of throwing an exception.

    - `:refresh?` (boolean)

      Always make a read for fresh data, even if a cached secret is
      available.

    - `:ttl` (integer)

      Cache the data read for the given number of seconds. A value of zero or
      less will disable caching.

    Note that Vault internally stores data as JSON, so not all Clojure types
    will round-trip successfully!")

  (write-secret!
    [client path data]
    [client path data opts]
    "Store data at the provided path, creating a new version of the secret.
    Returns the secret metadata.

    Options:

    - `:cas` (integer)

      If set, the write will only succeed if the current version of the secret
      matches this value. If set to `0`, it will only succeed if the key doesn't
      exist.

    Note that Vault internally stores data as JSON, so not all Clojure types
    will round-trip successfully!")

  (patch-secret!
    [client path data]
    [client path data opts]
    "Patch an existing secret at the provided location. The secret must neither
    be deleted nor destroyed. A new version will be created upon successfully
    applying a patch with the provided data. Returns the secret metadata.

    Options:

    - `:cas` (integer)

      If set, the update will only succeed if the current version of the secret
      matches this value.

    Note that Vault internally stores data as JSON, so not all Clojure types
    will round-trip successfully!")

  (delete-secret!
    [client path]
    "Delete the latest version of the secret at the provided path, if any.
    Returns `nil`.

    This is a soft-delete that may later be reverted with [[undelete-versions!]].")

  (destroy-secret!
    [client path]
    "Permanently delete the secret metadata and all version data for the given
    path. All version history will be removed. Returns `nil`.")

  (delete-versions!
    [client path versions]
    "Issue a soft delete of the specified versions of the secret. Returns
    `nil`.

    This marks the versions as deleted and will stop them from being returned
    from reads, but the underlying data will not be removed. A delete can be
    undone using the `undelete-versions!` method.")

  (undelete-versions!
    [client path versions]
    "Undelete the data for the provided versions of the secret. Returns `nil`.

    This restores the data, allowing it to be returned on get requests.")

  (destroy-versions!
    [client path versions]
    "Permanently remove the data for the provided secret and version numbers.
    Returns `nil`.")

  (read-metadata
    [client path]
    "Read the metadata and versions for the secret at the specified path.
    Metadata is version-agnostic.")

  (write-metadata!
    [client path opts]
    "Update the metadata of a secret at the specified path. Returns `nil`. This
    does not create a new version.

    Options:

    - `:max-versions` (integer)

      Number of versions to keep per key. Once the secret has more than the
      configured allowed versions, the oldest version will be permanently
      deleted.

    - `:cas-required` (boolean)

      If true, the key will require the cas parameter to be set on all write requests.

    - `:delete-version-after` (string)

      Duration string specifying the time after which all new versions written
      to this secret should be deleted. Accepts Go duration format strings.

    - `:custom-metadata` (map)

      Map of arbitrary string-to-string valued user-provided metadata meant to
      describe the secret.")

  (patch-metadata!
    [client path opts]
    "Patch the existing metadata for the secret at the provided location.
    Returns `nil`.

    See [[write-metadata!]] for options."))


;; ## Mock Client

(defn- qualify-keyword
  "Update a keyword so that it is namespaced by 'vault.secret.kv.v2'."
  [k]
  (keyword "vault.secret.kv.v2" (name k)))


(defn- ex-not-found
  "Construct a new not-found exception for the given mount and path."
  ([ex]
   (let [data (ex-data ex)]
     (ex-not-found (::mount data) (::path data) data)))
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
              uv (get u k)]
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
                            {::custom-metadata (u/keywordize-keys (json/read-str cm))}))))

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
           serialized (-> data
                          (u/stringify-keys)
                          (json/write-str))]
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
                               (apply max (keys (:versions secret)))
                               0)
                 version-id (inc max-version)
                 version {:data serialized
                          :created-time (u/now)
                          :destroyed false}]
             (-> (or secret {:created-time (u/now)})
                 (assoc-in [:versions version-id] version)
                 (assoc :current-version version-id
                        :updated-time (u/now)))))
         (fn secret-meta
           [secret]
           (let [current (:current-version secret)
                 version (get-in secret [:versions current])]
             (merge
               (select-keys secret [:custom-metadata])
               (select-keys version [:created-time :destroyed])
               {:version current})))))))


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
             (let [new-data (-> (:data version)
                                (json/read-str)
                                (u/keywordize-keys)
                                (mock-patch data)
                                (u/stringify-keys)
                                (json/write-str))
                   max-version (apply max (keys (:versions secret)))
                   version-id (inc max-version)
                   version {:data new-data
                            :created-time (u/now)
                            :destroyed false}]
               (-> secret
                   (assoc-in [:versions version-id] version)
                   (assoc :current-version version-id
                          :updated-time (u/now))))))
         (fn secret-meta
           [secret]
           (let [current (:current-version secret)
                 version (get-in secret [:versions current])]
             (merge
               (select-keys secret [:custom-metadata])
               (select-keys version [:created-time :destroyed])
               {:version current})))))))


  (delete-secret!
    [client path]
    (let [mount (::mount client default-mount)
          path (u/trim-path path)]
      (mock/update-secret!
        client
        [::data mount path]
        (fn update-secret
          [secret]
          (when secret
            (assoc-in secret [:versions (:current-version secret) :deletion-time] (u/now)))))))


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
          (when secret
            (assoc secret
                   :versions
                   (reduce
                     (fn set-deletions
                       [acc version-id]
                       (if (contains? acc version-id)
                         (assoc-in acc [version-id :deletion-time] (u/now))
                         acc))
                     (:versions secret)
                     versions)))))))


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
          (when secret
            (assoc secret
                   :versions
                   (reduce
                     (fn unset-deletions
                       [acc version-id]
                       (if (contains? acc version-id)
                         (update acc version-id dissoc :deletion-time)
                         acc))
                     (:versions secret)
                     versions)))))))


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
          (when secret
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
                     versions)))))))

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
              (select-keys [:max-versions
                            :cas-required
                            :created-time
                            :updated-time
                            :current-version
                            :custom-metadata
                            :delete-version-after])
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
                                     (:versions secret)))
              (u/update-some :custom-metadata (comp u/keywordize-keys json/read-str))))
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
          (when secret
            (-> secret
                (merge (select-keys opts
                                    [:max-versions
                                     :cas-required
                                     :delete-version-after]))
                (cond->
                  (:custom-metadata opts)
                  (assoc :custom-metadata
                         (-> (:custom-metadata opts)
                             (u/stringify-keys)
                             (update-vals str)
                             (json/write-str))))))))))


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
                (:custom-metadata opts)
                (assoc :custom-metadata
                       (-> (:custom-metadata secret)
                           (json/read-str)
                           (u/keywordize-keys)
                           (mock-patch (:custom-metadata opts))
                           (u/stringify-keys)
                           (update-vals str)
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


(defn- wrap-not-found
  "Handle an API exception and wrap 404s as a standard not-found exception.
  Other exceptions are returned as-is."
  [ex]
  (if (http/not-found? ex)
    (ex-not-found ex)
    ex))


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
        client ::list-secrets
        :list (u/join-path mount "metadata" path)
        {:info {::mount mount, ::path path}
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
           version (:version opts)
           info (cond-> {::mount mount, ::path path}
                  version
                  (assoc ::version version))
           cache-key [::secret mount path]
           cached (when-not (:refresh? opts)
                    (lease/find-data client cache-key))]
       (if (and cached
                (or (nil? version)
                    (= version (::version (meta cached)))))
         (http/cached-response client ::read-secret info cached)
         (http/call-api
           client ::read-secret
           :get (u/join-path mount "data" path)
           {:info info
            :query-params (if version
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
                             (vary-meta merge metadata))]
                (when lease
                  (lease/invalidate! client cache-key)
                  (lease/put! client lease data))
                (vary-meta data merge lease)))
            :handle-error
            (fn handle-error
              [ex]
              (if (http/not-found? ex)
                (if-let [[_ not-found] (find opts :not-found)]
                  (if (instance? IObj not-found)
                    (vary-meta not-found merge (ex-data ex))
                    not-found)
                  (ex-not-found ex))
                ex))})))))


  (write-secret!
    ([client path data]
     (write-secret! client path data nil))
    ([client path data opts]
     (let [mount (::mount client default-mount)
           path (u/trim-path path)
           cache-key [::secret mount path]]
       (lease/invalidate! client cache-key)
       (http/call-api
         client ::write-secret!
         :post (u/join-path mount "data" path)
         {:info {::mount mount, ::path path}
          :content-type :json
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
       (lease/invalidate! client cache-key)
       (http/call-api
         client ::patch-secret!
         :patch (u/join-path mount "data" path)
         {:info {::mount mount, ::path path}
          :headers {"content-type" "application/merge-patch+json"}
          :body (json/write-str
                  {:options (select-keys opts [:cas])
                   :data data})
          :handle-response parse-secret-metadata
          :handle-error wrap-not-found}))))


  (delete-secret!
    [client path]
    (let [mount (::mount client default-mount)
          path (u/trim-path path)
          cache-key [::secret mount path]]
      (lease/invalidate! client cache-key)
      (http/call-api
        client ::delete-secret!
        :delete (u/join-path mount "data" path)
        {:info {::mount mount, ::path path}})))


  (destroy-secret!
    [client path]
    (let [mount (::mount client default-mount)
          path (u/trim-path path)
          cache-key [::secret mount path]]
      (lease/invalidate! client cache-key)
      (http/call-api
        client ::destroy-secret!
        :delete (u/join-path mount "metadata" path)
        {:info {::mount mount, ::path path}})))


  (delete-versions!
    [client path versions]
    (let [mount (::mount client default-mount)
          path (u/trim-path path)
          cache-key [::secret mount path]]
      (lease/invalidate! client cache-key)
      (http/call-api
        client ::delete-versions!
        :post (u/join-path mount "delete" path)
        {:info {::mount mount, ::path path, ::versions versions}
         :content-type :json
         :body {:versions versions}})))


  (undelete-versions!
    [client path versions]
    (let [mount (::mount client default-mount)
          path (u/trim-path path)
          cache-key [::secret mount path]]
      (lease/invalidate! client cache-key)
      (http/call-api
        client ::undelete-versions!
        :post (u/join-path mount "undelete" path)
        {:info {::mount mount, ::path path, ::versions versions}
         :content-type :json
         :body {:versions versions}})))


  (destroy-versions!
    [client path versions]
    (let [mount (::mount client default-mount)
          path (u/trim-path path)
          cache-key [::secret mount path]]
      (lease/invalidate! client cache-key)
      (http/call-api
        client ::destroy-versions!
        :post (u/join-path mount "destroy" path)
        {:info {::mount mount, ::path path, ::versions versions}
         :content-type :json
         :body {:versions versions}})))


  (read-metadata
    [client path]
    (let [mount (::mount client default-mount)
          path (u/trim-path path)]
      (http/call-api
        client ::read-metadata
        :get (u/join-path mount "metadata" path)
        {:info {::mount mount, ::path path}
         :handle-response parse-secret-metadata
         :handle-error wrap-not-found})))


  (write-metadata!
    [client path opts]
    (let [mount (::mount client default-mount)
          path (u/trim-path path)]
      (http/call-api
        client ::write-metadata!
        :post (u/join-path mount "metadata" path)
        {:info {::mount mount, ::path path}
         :content-type :json
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
        client ::patch-metadata!
        :patch (u/join-path mount "metadata" path)
        {:info {::mount mount, ::path path}
         :headers {"content-type" "application/merge-patch+json"}
         :body (json/write-str
                 (-> opts
                     (dissoc :custom-metadata)
                     (u/snakify-keys)
                     (cond->
                       (seq (:custom-metadata opts))
                       (assoc :custom_metadata
                              (u/stringify-keys (:custom-metadata opts))))))
         :handle-error wrap-not-found}))))
