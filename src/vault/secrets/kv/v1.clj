(ns vault.secrets.kv.v1
  "The kv secrets engine is used to store arbitrary secrets within the
  configured physical storage for Vault. Writing to a key in the kv-v1 backend
  will replace the old value; sub-fields are not merged together.

  Reference: https://www.vaultproject.io/api-docs/secret/kv/kv-v1"
  (:require
    [clojure.string :as str]
    [vault.client.http :as http]
    [vault.client.mock :as mock]
    [vault.client.util :as u])
  (:import
    java.time.Instant
    vault.client.http.HTTPClient
    vault.client.mock.MockClient))


(def default-mount
  "Default mount point to use if one is not provided."
  "secret")


(defn- resolve-path
  "Resolve the provided path into a mount and a relative path component. Uses
  the default mount if none is specified."
  [path]
  (if (str/includes? path ":")
    (mapv u/trim-path (str/split path #":" 2))
    [default-mount (u/trim-path path)]))


;; ## API Protocol

(defprotocol API
  "The kv secrets engine is used to store arbitrary static secrets within
  Vault.

  All of the methods in this protocol expect `path` to be relative to the
  secret engine mount point, which defaults to `secret/`. To specify a
  different mount, prefix the path with the mount location and a colon, like
  `kv1:foo/bar`."

  ;; TODO: document behaviors:
  ;; - on nonexistent path, throws not-found
  ;; - on file, throws not-found
  (list-secrets
    [client path]
    "List the secret names located under a path prefix location. Folders are
    suffixed with `/`. The path must be a folder; calling this method on a file
    will not return a value.")

  ;; TODO: document behaviors:
  ;; - on nonexistent path, throws not-found
  ;; - on folder, throws not-found with a kv2 warning
  ;; TODO: lease controls/metadata?
  ;; TODO: note about JSON coercion
  (read-secret
    [client path]
    [client path opts]
    "Read the secret at the provided path. Returns the secret data, if
    present.")

  ;; TODO: what does this return?
  ;; TODO: note about special :ttl key
  ;; TODO: note about JSON coercion
  (write-secret!
    [client path data]
    "Store secret data at the provided path. This will overwrite any secret
    that was previously stored there.")

  ;; TODO: what does this return?
  (delete-secret!
    [client path]
    "Delete the secret at the provided path."))


;; ## Mock Client

(extend-type MockClient

  API

  (list-secrets
    [client path]
    (let [[mount path] (resolve-path path)
          data (get-in @(:memory client) [:secrets ::data mount])]
      (mock/success-response
        client
        (into []
              (filter #(str/starts-with? % (str path)))
              (keys data)))))


  (read-secret
    ([client path]
     (read-secret client path nil))
    ([client path _opts]
     (let [[mount path] (resolve-path path)]
       (if-let [secret (get-in @(:memory client) [:secrets ::data mount path])]
         (mock/success-response client secret)
         (mock/error-response
           client
           (ex-info (str "No kv.v1 secret found in " mount " at " path)
                    {:vault.secrets/mount mount
                     :vault.secrets/path path}))))))


  (write-secret!
    [client path data]
    (let [[mount path] (resolve-path path)]
      (swap! (:memory client) assoc-in [:secrets ::data mount path data])
      (mock/success-response client nil)))


  (delete-secret!
    [client path]
    (let [[mount path] (resolve-path path)]
      (swap! (:memory client) update-in [:secrets ::data mount] dissoc path)
      (mock/success-response client nil))))


;; ## HTTP Client

(extend-type HTTPClient

  API

  (list-secrets
    [client path]
    (let [[mount path] (resolve-path path)]
      (http/call-api
        client :get (u/join-path mount path)
        {:query-params {:list true}
         :handle-response
         (fn handle-response
           [body]
           (get-in body ["data" "keys"]))})))


  (read-secret
    ([client path]
     (read-secret client path nil))
    ([client path opts]
     (let [[mount path] (resolve-path path)]
       ;; TODO: check for a cached secret and re-use it
       ;; TODO: update lease cache if appropriate (note: no lease_id, substitute request_id)
       (http/call-api
         client :get (u/join-path mount path)
         {:handle-response
          (fn handle-response
            [body]
            (let [data (u/walk-keys (get body "data") keyword)
                  lease-duration (get body "lease_duration")
                  renewable? (get body "renewable")]
              (cond-> data
                (pos-int? lease-duration)
                (vary-meta data assoc
                           :vault.lease/duration lease-duration
                           :vault.lease/expires-at (.plusSeconds (u/now) lease-duration))

                (some? renewable?)
                (vary-meta assoc :vault.lease/renewable? renewable?))))}))))


  (write-secret!
    [client path data]
    (let [[mount path] (resolve-path path)]
      ;; TODO: invalidate lease cache
      (http/call-api
        client :post (u/join-path mount path)
        {:content-type :json
         :body data})))


  (delete-secret!
    [client path]
    (let [[mount path] (resolve-path path)]
      ;; TODO: invalidate lease cache
      (http/call-api
        client :delete (u/join-path mount path)
        {}))))
