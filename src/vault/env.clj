(ns vault.env
  "Code to resolve secrets from a map of environment configuration. This is
  useful for service startup configs using the app-id authentication scheme.

  Writing secrets to environmental configuration is supported via `write!`.

  If a client is not provided, the code initializes one from the `VAULT_URL`,
  `VAULT_APP_ID`, and `VAULT_USER_ID` environment variables."
  (:require
    [clojure.string :as str]
    [clojure.tools.logging :as log]
    [vault.client :as vault]))


(def vault-prefix "vault:")


(defn- init-client
  "Initialize and auth a new HTTP Vault client. Returns nil if the `:vault-url`
  is not configured. If it is, but the app-id or user-id are missing, throws an
  exception."
  [env]
  (when-let [url (env :vault-url)]
    (let [client (assoc (vault/http-client url) ::cache (atom {}))
          app-id (env :vault-app-id)
          user-id (env :vault-user-id)]
      (when-not (and app-id user-id)
        (throw (ex-info "Missing Vault app-id or user-id in environment settings!"
                        {:app-id app-id})))
      (vault/authenticate! client :app-id {:app app-id, :user user-id})
      client)))


(defn- uncache!
  "Uncaches a secret."
  [cache path]
  (when cache
    (swap! cache dissoc path)))


(defn- cache!
  "Caches a secret."
  [cache path secret]
  (when cache
    (swap! cache assoc path secret)))


(defn- read-secret
  "Reads a secret path from Vault, caching the value in the client."
  [client path]
  (let [cache (::cache client)]
    (or (and cache (get @cache path))
        (try
          (let [secret (vault/read-secret client path)]
            (cache! cache path secret)
            secret)
          (catch Exception ex
            (log/error ex "Failed to resolve environment secret:" path
                       (str (:body (ex-data ex))))
            (throw ex))))))


(defn- write-secret!
  "Writes a secret path to Vault, caching the value in the client.
  Throws if there is an error."
  [client path secret]
  (let [cache (::cache client)]
    (if (vault/write-secret! client path secret)
      (cache! cache path secret)
      (do
        (uncache! cache path)
        (throw (ex-info (str "Failed to write secret")
                        {:path path})))))
  nil)


(defn- resolve-uri
  "Resolves a Vault path URI as provided to the environment. Throws an exception
  if the URI does not resolve to a non-nil value."
  [client vault-uri]
  (when-not client
    (throw (ex-info "Cannot resolve secret without initialized client"
                    {:uri vault-uri})))
  (let [[path attr] (str/split (subs vault-uri (count vault-prefix)) #"#")
        secret (read-secret client path)
        attr (or (keyword attr) :data)
        value (get secret attr)]
    (when-not (some? value)
      (throw (ex-info (str "No value for secret " vault-uri)
                      {:path path, :attr attr})))
    value))


(defn resolve-secrets
  "Looks up the given collection of environment variables in Vault if the
  values are prefixed with `vault:` and the `:vault-url` variable has a value."
  [client env secrets]
  (reduce
    (fn resolve-var
      [env' k]
      (if-let [v (get env' k)]
        ; Does the env value start with the Vault prefix?
        (if (str/starts-with? v vault-prefix)
          (assoc env' k (resolve-uri client v))
          env')
        ; Value for k is not configured in env.
        env'))
    env secrets))


(defn load!
  "Retrieves environmental configuration and returns a map of configuration
  variables. If there is an error, the system will exit."
  ([env secrets]
   (load! nil env secrets))
  ([client env secrets]
   (if (seq secrets)
     ; Some secrets, resolve paths.
     (let [client (or client (init-client env))]
       (resolve-secrets client env secrets))
     ; No secrets, return env directly.
     env)))


(defn write!
  "Writes a secret to environmental configuration. `secret` should be a map. 
  Throws if there is an error."
  ([env path secret]
   (write! nil env path secret))
  ([client env path secret]
   (let [client (or client (init-client env))]
     (write-secret! client path secret))))
