(ns vault.client.api-util
  (:require
    [cheshire.core :as json]
    [clj-http.client :as http]
    [clojure.string :as str]
    [clojure.tools.logging :as log]
    [clojure.walk :as walk])
  (:import
    (clojure.lang
      ExceptionInfo)
    (java.security
      MessageDigest)
    (org.apache.commons.codec.binary
      Hex)))

;; ## API Utilities

(defmacro supports-not-found
  "Tries to perform the body, which likely includes an API call. If a `404` `::api-error` occurs, looks for and returns
  the value of `:not-found` in `on-fail-opts` if present"
  [on-fail-opts & body]
  `(try
     ~@body
     (catch ExceptionInfo ex#
       (let [api-fail-options# ~on-fail-opts]
         (if (and (contains? api-fail-options# :not-found)
                  (= ::api-error (:type (ex-data ex#)))
                  (= 404 (:status (ex-data ex#))))
           (:not-found api-fail-options#)
           (throw ex#))))))


(defn ^:no-doc kebabify-keys
  "Rewrites keyword map keys with underscores changed to dashes."
  [value]
  (let [kebab-kw #(-> % name (str/replace "_" "-") keyword)
        xf-entry (juxt (comp kebab-kw key) val)]
    (walk/postwalk
      (fn xf-maps
        [x]
        (if (map? x)
          (into {} (map xf-entry) x)
          x))
      value)))


(defn ^:no-doc sha-256
  "Geerate a SHA-2 256 bit digest from a string."
  [s]
  (let [hasher (MessageDigest/getInstance "SHA-256")
        str-bytes (.getBytes (str s) "UTF-8")]
    (.update hasher str-bytes)
    (Hex/encodeHexString (.digest hasher))))


(defn ^:no-doc clean-body
  "Cleans up a response from the Vault API by rewriting some keywords and
  dropping extraneous information. Note that this changes the `:data` in the
  response to the original result to preserve accuracy."
  [response]
  (->
    (:body response)
    (kebabify-keys)
    (assoc :data (:data (:body response)))
    (->> (into {} (filter (comp some? val))))))


(defn ^:no-doc api-error
  "Inspects an exception and returns a cleaned-up version if the type is well
  understood. Otherwise returns the original error."
  [ex]
  (let [data (ex-data ex)
        status (:status data)]
    (if (and status (<= 400 status))
      (let [body (try
                   (json/parse-string (:body data) true)
                   (catch Exception _
                     nil))
            errors (if (:errors body)
                     (str/join ", " (:errors body))
                     (pr-str body))]
        (ex-info (str "Vault API errors: " errors)
                 {:type ::api-error
                  :status status
                  :errors (:errors body)}
                 ex))
      ex)))


(defn ^:no-doc do-api-request
  "Performs a request against the API, following redirects at most twice. The
  `request-url` should be the full API endpoint."
  [method request-url req]
  (let [redirects (::redirects req 0)]
    (when (<= 2 redirects)
      (throw (ex-info (str "Aborting Vault API request after " redirects " redirects")
                      {:method method, :url request-url})))
    (let [resp (try
                 (http/request (assoc req :method method :url request-url))
                 (catch Exception ex
                   (throw (api-error ex))))]
      (if-let [location (and (#{303 307} (:status resp))
                             (get-in resp [:headers "Location"]))]
        (do (log/debug "Retrying API request redirected to " location)
            (recur method location (assoc req ::redirects (inc redirects))))
        resp))))


(defn ^:no-doc api-request
  "Helper method to perform an API request with common headers and values.
  Currently always uses API version `v1`. The `path` should be relative to the
  version root."
  [client method path req]
  ; Check API path.
  (when-not (and (string? path) (not (empty? path)))
    (throw (IllegalArgumentException.
             (str "API path must be a non-empty string, got: "
                  (pr-str path)))))
  ; Check client authentication.
  (when-not (some-> client :auth deref :client-token)
    (throw (IllegalStateException.
             "Cannot call API path with unauthenticated client.")))
  ; Call API with standard arguments.
  (do-api-request
    method
    (str (:api-url client) "/v1/" path)
    (merge
      (:http-opts client)
      {:accept :json
       :as :json}
      req
      {:headers (merge {"X-Vault-Token" (:client-token @(:auth client))}
                       (:headers req))})))


(defn ^:no-doc unwrap-secret
  "Common function to call the token unwrap endpoint."
  [client wrap-token]
  (do-api-request
    :post (str (:api-url client) "/v1/sys/wrapping/unwrap")
    (merge
      (:http-opts client)
      {:headers {"X-Vault-Token" wrap-token}
       :content-type :json
       :accept :json
       :as :json})))
