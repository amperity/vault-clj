Upgrading from 1.x
==================

If you're upgrading from the 1.x major version, there are a number of
differences to account for.


## Build and Dependencies

The coordinate for the library has changed from `amperity/vault-clj` to
`com.amperity/vault-clj`, in keeping with Clojars' new domain verification
requirements. Additionally, the dependencies used by the library are much
lighter weight now:

- Use `org.clojure/data.json` for JSON serialization now instead of `cheshire`,
  avoiding messy Jackson dependencies.
- Dropped dependency on `com.stuartsierra/component`.
- Dropped dependency on `envoy`.


## Client Protocols

Many of the protocols and methods previously in `vault.core` have moved to
backend-specific namespaces. For example:

- `TokenManager` is now `vault.auth.token/API`
- `LeaseManager` is now `vault.sys.leases/API`
- `WrappingClient` is now `vault.sys.wrapping/API`
- `SecretEngine` is replaced by engine-specific protocols in `vault.secret.*`

The two previously implemented secrets engines have moved slightly:

- `vault.secrets.kvv1` is now `vault.secret.kv.v1`
- `vault.secrets.kvv2` is now `vault.secret.kv.v2`

For the KV secrets engines, previously a `list-secrets` call would return a
vector of the keys at that prefix directly. Now, these methods return a map
with a `:keys` vector entry if there are secrets present, matching the actual
API response shape.

### Mounts

In 1.x, reading a secret from a customized mount required embedding the mount
prefix in the secret path at read time. Now, each secret engine provides a
`with-mount` method which returns an updated client which will perform reads
against the specified mount. This lets customization happen at configuration
time and decouples the code using the client from knowledge of the mount path.


## Authentication

Rather than a single multimethod in `vault.authenticate`, client authentication
is now performed by calling method-specific protocols. For example, if you were
previously using the `userpass` method:

```clojure
(require '[vault.core :as vault])

(def client (vault/new-client "..."))

(vault/authenticate! client :userpass {:username "bob", :password "hunter2"}
```

This now looks like:

```clojure
(require '[vault.client :as vault]
         '[vault.auth.userpass :as userpass])

(def client (vault/new-client "..."))

(userpass/login client "bob" "hunter2")
```


## Renewal and Rotation

In 1.x, the client would renew and rotate secrets as they approached expiry.
This was done on a single background thread running as part of the client
state. If consumers needed to react to lifecycle events, they could register a
"lease watch", which would be invoked when the lease changed. While this worked
as a hook for rotated credentials to be updated in whatever system was using
them, it was a bit clunky and had some problems:
- It didn't handle more nuanced outcomes like failures well. Even expiration
  just called the watch function with `nil`.
- The calling code also had to know up front what secret path to register the
  watch for, coupling it to the underlying Vault API.
- The watches ran on the same timer thread, so a slow callback could block
  other watches and even future lease maintenance.

In 2.x, things are a bit different. Instead of a single thread, the client now
supports setting a `maintenance-executor` and a `callback-executor`. The
maintenance executor is responsible for running a periodic task to perform the
interactions with Vault, while any callbacks are passed to the callback
executor. If not provided, callbacks run on the same thread pool as a regular
Clojure `future`. This gives consumers more control over how these tasks are
run, as well as preventing the periodic task from getting blocked.

Instead of a separate method to register callbacks, users can pass a set of
callback functions to the method used to read the secret. For example, in
`vault.secret.database/generate-credentials!` a caller can specify `:on-renew`,
`:on-rotate`, and `:on-error` functions to handle each outcome. Generally,
the rotation callback would replace the previous use of a lease watcher.


## Component Lifecycle

For flexibility, the library no longer depends on `com.stuartsierra/component`
and clients no longer implement the `Lifecycle` protocol. Instead, the
lifecycle methods are available as the regular functions `vault.client/start`
and `vault.client/stop`.

If you want to continue using `component` as a dependency injection library,
you can use the following code to reestablish the previous behavior:

```clojure
(require '[vault.client :as vault]
         '[com.stuartsierra.component :as component])


(extend vault.client.http.HTTPClient

  component/Lifecycle

  {:start vault/start
   :stop vault/stop})
```


## Environment Resolution

The `vault.env` environment variable resolution code has been removed to
decouple the library from `envoy`. This can be replicated locally in your
project with code like the following:

```clojure
(require '[clojure.string :as str])


(defn secret-uri?
  [s]
  (and (string? s) (str/starts-with? s "vault:")))


(defn resolve-uri!
  [client vault-uri]
  (let [[path attr] (str/split (subs vault-uri 6) #"#")
        secret (kv/read-secret client path)
        attr (or (keyword attr) :data)
        value (get secret attr)]
    (when (nil? value)
      (throw (ex-info (str "No value for secret " vault-uri)
                      {:path path, :attr attr})))
    value))


(defn resolve-env-secrets!
  [client env]
  (into {}
        (map (fn resolve-var
               [[k v]]
               (if (secret-uri? v)
                 [k (resolve-uri! client v)]
                 [k v])))
        env))
```


## Misc

The client no longer throws an error when you make calls without
authentication, to support vault agent usage.
[#63](https://github.com/amperity/vault-clj/issues/63)
