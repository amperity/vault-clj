(ns vault.env
  "Code to resolve secrets from a map of environment configuration. This is
  useful for service startup configs using the app-id authentication scheme.

  If a client is not provided, the code initializes one from the `VAULT_URL`,
  `VAULT_APP_ID`, and `VAULT_USER_ID` environment variables."
  (:require
    [clojure.string :as str]
    [clojure.tools.logging :as log]
    [vault.client :as vault]))


(def vault-prefix "vault:")


(defn init-app-client
  "Initialize and auth a new HTTP Vault client. Returns nil if the `:vault-addr`
  is not configured. If it is, but the app-id or user-id are missing, throws an
  exception."
  [env]
  (when-let [url (or (env :vault-addr) (env :vault-url))]
    (let [client (vault/http-client url)
          app-id (env :vault-app-id)
          user-id (env :vault-user-id)]
      (when-not (and app-id user-id)
        (throw (ex-info "Missing Vault app-id or user-id in environment settings!"
                        {:app-id app-id})))
      (vault/authenticate! client :app-id {:app app-id, :user user-id})
      client)))


(defn resolve-uri
  "Resolves a Vault path URI as provided to the environment. Throws an exception
  if the URI does not resolve to a non-nil value."
  [client vault-uri]
  (when-not client
    (throw (ex-info "Cannot resolve secret without initialized client"
                    {:uri vault-uri})))
  (let [[path attr] (str/split (subs vault-uri (count vault-prefix)) #"#")
        secret (vault/read-secret client path)
        attr (or (keyword attr) :data)
        value (get secret attr)]
    (when (nil? value)
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
     (let [client (or client (init-app-client env))]
       (resolve-secrets client env secrets))
     ; No secrets, return env directly.
     env)))
