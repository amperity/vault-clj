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
    vault.client.http.HTTPClient
    vault.client.mock.MockClient))


(def default-mount
  "Default mount point to use if one is not provided."
  "secret")


;; ## API Protocol

;; TODO: how to best express mount customization?
(defprotocol API
  "The kv secrets engine is used to store arbitrary static secrets within
  Vault."

  ;; TODO: document behaviors:
  ;; - on nonexistent path, throws not-found
  ;; - on file, throws not-found
  (list-secrets
    [client path]
    [client mount path]
    "List the secret names located under a path prefix location. Folders are
    suffixed with `/`. The path must be a folder; calling this method on a file
    will not return a value.")

  ;; TODO: document behaviors:
  ;; - on nonexistent path, throws not-found
  ;; - on folder, throws not-found with a kv2 warning
  ;; TODO: lease controls/metadata?
  ;; TODO: note about JSON coercion
  (read-secret
    [client path opts]
    [client mount path opts]
    "Read the secret at the provided path. Returns the secret data, if
    present.")

  ;; TODO: what does this return?
  ;; TODO: note about special :ttl key
  ;; TODO: note about JSON coercion
  (write-secret!
    [client path data]
    [client mount path data]
    "Store secret data at the provided path. This will overwrite any secret
    that was previously stored there.")

  ;; TODO: what does this return?
  (delete-secret!
    [client path]
    [client mount path]
    "Delete the secret at the provided path."))


;; ## Mock Client

(extend-type MockClient

  API

  (list-secrets
    ([client path]
     (list-secrets client default-mount path))
    ([client mount path]
     (let [data (get-in @(:memory client) [:secrets ::data mount])]
       (mock/success-response
         client
         (into []
               (filter #(str/starts-with? % (str path)))
               (keys data))))))


  (read-secret
    ([client path opts]
     (read-secret client default-mount path opts))
    ([client mount path _opts]
     (if-let [secret (get-in @(:memory client) [:secrets ::data mount path])]
       (mock/success-response client secret)
       (mock/error-response
         client
         (ex-info (str "No kv.v1 secret found in " mount " at " path)
                  {:vault.secrets/mount mount
                   :vault.secrets/path path})))))


  (write-secret!
    ([client path data]
     (write-secret! client default-mount path data))
    ([client mount path data]
     (swap! (:memory client) assoc-in [:secrets ::data mount path data])
     (mock/success-response client nil)))


  (delete-secret!
    ([client path]
     (delete-secret! client default-mount path))
    ([client mount path]
     (swap! (:memory client) update-in [:secrets ::data mount] dissoc path)
     (mock/success-response client nil))))


;; ## HTTP Client

(extend-type HTTPClient

  API

  (list-secrets
    ([client path]
     (list-secrets client default-mount path))
    ([client mount path]
     ;; TODO: attach request-id to response metadata
     (http/call-api
       client :get (u/join-path mount path)
       {:query-params {:list true}
        :handle-response identity
        ,,,})))


  (read-secret
    ([client path opts]
     (read-secret client default-mount path opts))
    ([client mount path opts]
     ;; TODO: check for a cached lease and re-use it
     ;; TODO: update lease cache if appropriate
     (http/call-api
       client :get (u/join-path mount path)
       {:handle-response identity
        ,,,})))


  (write-secret!
    ([client path data]
     (write-secret! client default-mount path data))
    ([client mount path data]
     ;; TODO: invalidate lease cache
     (http/call-api
       client :post (u/join-path mount path)
       {:content-type :json
        :body data
        :handle-response identity
        ,,,})))


  (delete-secret!
    ([client path]
     (delete-secret! client default-mount path))
    ([client mount path]
     ;; TODO: invalidate lease cache
     (http/call-api
       client :delete (u/join-path mount path)
       {:handle-response identity
        ,,,}))))
