(ns ^:no-doc vault.util
  "Vault implementation utilities."
  (:require
    [clojure.string :as str]
    [clojure.walk :as walk])
  (:import
    java.time.Instant
    java.util.Base64))


;; ## Misc

(defn update-some
  "Apply the function `f` to the map value at `k` and any additional `args`,
  only if `m` contains `k`. Returns the updated map."
  [m k f & args]
  (if-let [[k* v] (find m k)]
    (assoc m k* (apply f v args))
    m))


(defn validate
  "Validate whether a map of data adheres to the validators set for the
  individual keys. Returns true if the map is valid, false otherwise. All keys
  are treated as optional, and any additional keys are not checked."
  [spec m]
  (and (map? m)
       (reduce-kv
         (fn check-key
           [_ k v]
           (if-let [pred (get spec k)]
             (if (pred v)
               true
               (reduced false))
             true))
         true
         m)))


;; ## Keywords

(defn walk-keys
  "Update the provided data structure by calling `f` on each map key."
  [data f]
  (walk/postwalk
    (fn xform
      [x]
      (if (map? x)
        (into (empty x)
              (map (juxt (comp f key) val))
              x)
        x))
    data))


(defn keywordize-keys
  "Update the provided data structure by coercing all map keys to keywords."
  [data]
  (walk-keys data keyword))


(defn kebab-keyword
  "Converts underscores to hyphens in a string or unqualified keyword. Returns
  a simple kebab-case keyword."
  [k]
  (-> k name (str/replace "_" "-") keyword))


(defn kebabify-keys
  "Walk the provided data structure by transforming map keys to kebab-case
  keywords."
  [data]
  (walk-keys data kebab-keyword))


(defn kebabify-body-auth
  "Look up a map in the provided body under the `\"auth\"` key and kebabify
  it."
  [body]
  (kebabify-keys (get body "auth")))


(defn kebabify-body-data
  "Look up a map in the provided body under the `\"data\"` key and kebabify
  it."
  [body]
  (kebabify-keys (get body "data")))


(defn snake-str
  "Converts hyphens to underscores in a string or keyword. Returns a snake-case
  string."
  [k]
  (-> k name (str/replace "-" "_")))


(defn snakify-keys
  "Walk the provided data structure by transforming map keys to snake_case
  strings."
  [data]
  (walk-keys data snake-str))


(defn stringify-key
  "Convert a map key into a string, with some special treatment for keywords."
  [k]
  (if (keyword? k)
    (subs (str k) 1)
    (str k)))


(defn stringify-keys
  "Walk the provided data structure to transform map keys to strings."
  [data]
  (walk-keys data stringify-key))


;; ## Encoding

(defn hex-encode
  "Encode an array of bytes to hex string."
  ^String
  [^bytes data]
  (str/join (map #(format "%02x" %) data)))


(defn base64-encode
  "Encode the given data as base-64. If the input is a string, it is
  automatically coerced into UTF-8 bytes."
  [data]
  (.encodeToString
    (Base64/getEncoder)
    ^bytes
    (cond
      (bytes? data)
      data

      (string? data)
      (.getBytes ^String data "UTF-8")

      :else
      (throw (IllegalArgumentException.
               (str "Don't know how to base64-encode value with type: "
                    (class data) " (expected a string or byte array)"))))))


(defn base64-decode
  "Decode the given base-64 string into byte or string data."
  ([data]
   (base64-decode data false))
  ([data as-string?]
   (when-not (string? data)
     (throw (IllegalArgumentException.
              (str "Don't know how to base64-decode value with type: "
                   (class data) " (expected a string)"))))
   (let [bs (.decode (Base64/getDecoder) ^String data)]
     (if as-string?
       (String. bs "UTF-8")
       bs))))


;; ## Paths

(defn trim-path
  "Remove any leading and trailing slashes from a path string."
  [path]
  (str/replace path #"^/+|/+$" ""))


(defn join-path
  "Join a number of path segments together with slashes, after trimming them."
  [& parts]
  (trim-path (str/join "/" (map trim-path parts))))


;; ## Time

(defn now
  "Returns the current time as an `Instant`."
  ^Instant
  []
  (Instant/now))


(defn now-milli
  "Return the current time in epoch milliseconds."
  []
  (.toEpochMilli (now)))


(defmacro with-now
  "Evaluate the body of expressions with `now` bound to the provided
  instant. Mostly useful for rebinding in tests."
  [inst & body]
  `(with-redefs [now (constantly ~inst)]
     ~@body))


;; ## Secret Protection

#?(:bb nil
   :clj (deftype Veil [value]))


(defn veil
  "Wrap the provided value in an opaque type which will not reveal its contents
  when printed. No effect in babashka."
  [x]
  #?(:bb x
     :clj (->Veil x)))


(defn unveil
  "Unwrap the hidden value if `x` is veiled. Otherwise, returns `x` directly."
  [x]
  #?(:bb x
     :clj (if (instance? Veil x)
            (.-value ^Veil x)
            x)))
