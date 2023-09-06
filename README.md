vault-clj
=========

[![CircleCI](https://circleci.com/gh/amperity/vault-clj.svg?style=shield&circle-token=874076b19570f775bb30fbb0eaa1e605116facf5)](https://circleci.com/gh/amperity/vault-clj)
[![codecov](https://codecov.io/gh/amperity/vault-clj/branch/main/graph/badge.svg)](https://codecov.io/gh/amperity/vault-clj)
[![cljdoc](https://cljdoc.org/badge/com.amperity/vault-clj)](https://cljdoc.org/d/com.amperity/vault-clj/CURRENT)

A Clojure library for interacting with the [Vault](https://vaultproject.io/)
secret management system. Most of the non-administrative API is implemented,
including the token authentication backend.


## Installation

Library releases are published on Clojars. To use the latest version with
Leiningen, add the following dependency to your project definition:

[![Clojars Project](http://clojars.org/com.amperity/vault-clj/latest-version.svg)](http://clojars.org/com.amperity/vault-clj)


## Usage

Using `vault-clj` involves first creating a client, then calling the API
protocols you want to interact with on it. As a user, you'll most likely
be utilizing a few high-level namespace groups:

- `vault.client` - The main public namespace for creating Vault client objects.
- `vault.auth.*` - [Authentication methods](https://developer.hashicorp.com/vault/api-docs/auth) such as token, approle, github, etc.
- `vault.secret.*` - [Secrets engines](https://developer.hashicorp.com/vault/api-docs/secret) such as kv, database, transit, etc.
- `vault.sys.*` - [System backend](https://developer.hashicorp.com/vault/api-docs/system) interfaces such as health, leases, wrapping, etc.

### Client Construction

Let's start by creating a new client, assuming a locally-running development server:

```clojure
;; Pull in namespace
=> (require '[vault.client :as vault])

;; If VAULT_ADDR is set we could use `config-client` to also automatically
;; authenticate with VAULT_TOKEN or the ~/.vault-token file.
=> (def client (vault/new-client "http://localhost:8200"))

;; Unless your process is very short-lived, you'll probably want to 'start' the
;; client to initiate background maintenance and callback tasks. Typically this
;; and 'stop' happen as part of a dependency-injection system.
=> (alter-var-root #'client vault/start)
```

### Authentication

Vault supports a number of authentication methods for obtaining an access
token. The most basic mechanism is to directly set a token on the client, but
many options are available.

```clojure
;; For simplicity, we can authenticate the client using a fixed token:
=> (vault/authenticate! client "t0p-53cr3t")

;; If we wanted to utilize approle for programmatic auth:
=> (require '[vault.auth.approle :as approle])

=> (approle/login client "my-cool-role" (System/getenv "VAULT_ROLE_SECRET"))
```

See the individual auth method namespace docs for information on the method you
want to use.

### Secrets Engines

Now that we've got an authenticated client to work with, we can start
interacting with the server.

```clojure
;; KVv2 is enabled by default as the basic engine for storing static secrets.
=> (require '[vault.secret.kv.v2 :as kv])

;; Initially, there are no secrets:
=> (kv/list-secrets client "")
nil

;; Trying to read a secret that doesn't exist throws an exception by default:
=> (kv/read-secret client "not/here")
;; Execution error (ExceptionInfo) at vault.secret.kv.v2/ex-not-found (v2.clj:178).
;; No kv-v2 secret found at secret:not/here

;; You can provide an explicit value to use for missing secrets instead:
=> (kv/read-secret client "not/here" {:not-found :missing})
:missing

;; Let's store a new secret, which returns information about the result:
=> (kv/write-secret! client "foo/bar" {:alpha "abc", :num 123, :kw :xyz})
{:created-time #inst "2023-09-06T18:49:14.728549Z"
 :destroyed false
 :version 1}

;; Most client responses contain metadata about the underlying HTTP request:
=> (meta *1)
{:vault.client/status 200
 :vault.client/request-id "6ad4fed6-21bc-c4c1-57cb-84def9ff7303"
 :vault.client/headers {:date "Wed, 06 Sep 2023 18:49:14 GMT"
                        :content-length "276"
                        :content-type "application/json"
                        :cache-control "no-store"}}

;; Now we can see our secret in the listing:
=> (kv/list-secrets client "")
{:keys ["foo/"]}

=> (kv/list-secrets client "foo/")
{:keys ["bar"]}

;; Let's read the secret data back! There's one gotcha here, which is that
;; Vault serializes secret data to JSON, so our keyword value has been
;; stringified during the round-trip:
=> (kv/read-secret client "foo/bar")
{:alpha "abc", :kw "xyz", :num 123}

;; As before, the response has metadata about the client call and this time,
;; the secret itself:
{:vault.client/status 200
 :vault.client/request-id "65eefa88-7cba-3345-0e11-f203c632d066"
 :vault.client/headers {,,,}
 :vault.secret.kv.v2/mount "secret"
 :vault.secret.kv.v2/path "foo/bar"
 :vault.secret.kv.v2/version 1
 :vault.secret.kv.v2/created-time #inst "2023-09-06T18:49:14.728549Z"
 :vault.secret.kv.v2/custom-metadata nil
 :vault.secret.kv.v2/destroyed false}
```

Each secrets engine defines its own protocol, so refer to their documentation
for how to interact with them. The goal is for these protocols to adhere
closely to the documented Vault APIs in structure and arguments.

### Babashka

The library is compatible with [babashka](https://babashka.org/) for lightweight
Vault integration. See the `bb.edn` file for an example to get you started. It
implements a basic task that reads a secret from Vault and prints it.

```shell
export VAULT_ADDR=your-vault-server-path
export VAULT_AUTH=token
export VAULT_TOKEN=token-value

bb vault-get
```


## Local Development

The client code can all be exercised in a REPL against a local development
Vault server. In most cases this is as simple as running `bin/server` and
`bin/repl`. See the [development doc](doc/development.md) for more detailed
instructions.


## License

Copyright © 2016-2023 Amperity, Inc

Distributed under the Apache License, Version 2.0. See the LICENSE file
for more information.
