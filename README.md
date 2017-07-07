vault-clj
=========

[![CircleCI](https://circleci.com/gh/amperity/vault-clj.svg?style=shield&circle-token=874076b19570f775bb30fbb0eaa1e605116facf5)](https://circleci.com/gh/amperity/vault-clj)
[![codecov](https://codecov.io/gh/amperity/vault-clj/branch/develop/graph/badge.svg)](https://codecov.io/gh/amperity/vault-clj)
[![API documentation](https://img.shields.io/badge/doc-API-blue.svg)](https://amperity.github.io/vault-clj/api/)

A Clojure library for interacting with the [Vault](https://vaultproject.io/)
secret management system. Most of the non-administrative API is implemented,
including the token authentication backend.

## Installation

Library releases are published on Clojars. To use the latest version with
Leiningen, add the following dependency to your project definition:

[![Clojars Project](http://clojars.org/amperity/vault-clj/latest-version.svg)](http://clojars.org/amperity/vault-clj)

## Usage

```clojure
; Pull in the main namespace and the HTTP client implementation:
=> (require '[vault.core :as vault] 'vault.client.http)

=> (def client (vault/new-client "https://vault.example.com"))

=> client
#vault.client.http.HTTPClient
{:api-url "https://vault.example.com",
 :auth #<Atom@5cca1513 nil>
 :lease-timer nil
 :leases #<Atom@640b3e30 {}>}

=> (vault/authenticate! client :app-id {:app "my_app", :user "0000-userid-000"})
; INFO: Successfully authenticated to Vault app-id my_app for policies: my-policy
#vault.client.http.HTTPClient
{:api-url "https://vault.example.com",
 :auth #<Atom@5cca1513 {:client-token "8c807a17-7232-4c48-d7a6-c6a7f76bcccc"}>
 :lease-timer nil
 :leases #<Atom@640b3e30 {}>}

=> (vault/read-secret client "secret/foo/bar")
{:data "baz qux"}
```

In addition to the standard HTTP client, there is a mock client available for
local testing. This can be constructed directly or using `mock` as the URL
scheme passed to the client constructor. The remainder of the URI should either
be `-` for an empty client, or may be a path to an EDN file containing the
secret fixture data.

```clojure
=> (require 'vault.client.mock)

=> (read-string (slurp "dev/secrets.edn"))
{"secret/service/foo/login" {:user "foo", :pass "abc123"}}

=> (def mock-client (vault/new-client "mock:dev/secrets.edn"))

=> (vault/read-secret mock-client "secret/service/foo/login")
{:user "foo", :pass "abc123"}
```

## Environment Resolution

In order to abstract away the source of sensitive configuration variables
provided to code, the `vault.env` namespace can be used to bootstrap a Vault
client and resolve a map of config variables to their secret values.

```clojure
=> (require '[vault.env :as venv])

; Construct and authenticate a client from the environment. Looks for
; :vault-addr, :vault-token, :vault-app-id, etc.
=> (def client (venv/config-client {:vault-addr "mock:dev/secrets.edn"}))

=> (venv/load!
     client
     {:foo-user "vault:secret/service/foo/login#user"
      :foo-pass "vault:secret/service/foo/login#pass"
      :bar "direct-value"}
     [:foo-user :foo-pass :bar])
{:foo-user "foo"
 :foo-pass "abc123"
 :bar "direct-value}
```

## License

Copyright © 2016 Amperity, Inc

Distributed under the Apache License, Version 2.0. See the LICENSE file
for more information.
